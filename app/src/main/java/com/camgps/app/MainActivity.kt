package com.camgps.app

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.camgps.app.databinding.ActivityMainBinding
import com.camgps.app.databinding.DialogPhotoPreviewBinding
import com.camgps.app.location.LocationHelper
import com.camgps.app.model.LocationData
import com.camgps.app.utils.MotionPhotoHelper
import com.camgps.app.utils.StorageHelper
import com.camgps.app.watermark.StaticMapHelper
import com.camgps.app.watermark.WatermarkDrawer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraEffect
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class CameraMode {
    LIVE_PHOTO,
    PHOTO,
    VIDEO
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var locationHelper: LocationHelper
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var camera: Camera? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var flashMode = ImageCapture.FLASH_MODE_AUTO

    private var currentMode = CameraMode.PHOTO
    private var currentLocationData = LocationData()
    private var currentMapBitmap: Bitmap? = null

    private var timeUpdateJob: Job? = null
    private var videoTimerJob: Job? = null
    private var videoDurationSeconds = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val locationGranted = (permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false) ||
                (permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false)

        if (cameraGranted && locationGranted) {
            bindCameraUseCases()
            startLocation()
        } else {
            showPermissionExplanationDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots and screen recordings while the app is open
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        locationHelper = LocationHelper(this)

        setupUI()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startLocation()
        }
        startTimeUpdater()
    }

    override fun onPause() {
        super.onPause()
        locationHelper.stopLocationUpdates()
        timeUpdateJob?.cancel()
        if (currentRecording != null) {
            stopVideoRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        currentMapBitmap?.recycle()
    }

    private fun setupUI() {
        // Flash Mode Toggle
        binding.btnFlash.setOnClickListener {
            cycleFlashMode()
        }

        // Camera Switch (Front/Back)
        binding.btnSwitchCamera.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            bindCameraUseCases()
        }

        // Camera Modes: LIVE_PHOTO, PHOTO, VIDEO
        binding.tvModeLivePhoto.setOnClickListener { switchCameraMode(CameraMode.LIVE_PHOTO) }
        binding.tvModePhoto.setOnClickListener { switchCameraMode(CameraMode.PHOTO) }
        binding.tvModeVideo.setOnClickListener { switchCameraMode(CameraMode.VIDEO) }

        // Shutter Button (Photo capture, Live Photo, or Video record)
        binding.btnCapture.setOnClickListener {
            when (currentMode) {
                CameraMode.PHOTO -> takePhoto()
                CameraMode.LIVE_PHOTO -> takeLivePhoto()
                CameraMode.VIDEO -> toggleVideoRecording()
            }
        }

        // GPS Info Button (Right side)
        binding.btnGpsInfo.setOnClickListener {
            if (!locationHelper.isGpsEnabled()) {
                promptEnableGps()
            } else {
                val acc = if (currentLocationData.hasGpsLock) {
                    "±${currentLocationData.displayAccuracy}m"
                } else "Acquiring..."
                Toast.makeText(
                    this,
                    "GPS Status: ${if (currentLocationData.hasGpsLock) "Locked" else "Searching"}\nAccuracy: $acc\n${currentLocationData.formattedCoordinates}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Observe Real-time Location Updates
        lifecycleScope.launch {
            locationHelper.locationFlow.collect { locData ->
                currentLocationData = locData
                updateLiveStampUI(locData)
                updateGpsStatusChip(locData)

                // Refresh mini map preview when location is acquired
                if (locData.hasGpsLock) {
                    refreshMapPreview(locData.latitude, locData.longitude)
                }
            }
        }
    }

    private fun switchCameraMode(mode: CameraMode) {
        if (currentRecording != null) {
            Toast.makeText(this, "Please stop recording first", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentMode == mode) return
        currentMode = mode

        // Update mode selector tabs visual styling
        binding.tvModeLivePhoto.setTextColor(if (mode == CameraMode.LIVE_PHOTO) Color.parseColor("#FFD600") else Color.parseColor("#88FFFFFF"))
        binding.tvModePhoto.setTextColor(if (mode == CameraMode.PHOTO) Color.parseColor("#FFD600") else Color.parseColor("#88FFFFFF"))
        binding.tvModeVideo.setTextColor(if (mode == CameraMode.VIDEO) Color.parseColor("#FFD600") else Color.parseColor("#88FFFFFF"))

        // Update shutter icon
        when (mode) {
            CameraMode.PHOTO -> binding.btnCapture.setImageResource(R.drawable.ic_shutter)
            CameraMode.LIVE_PHOTO -> binding.btnCapture.setImageResource(R.drawable.ic_shutter_live)
            CameraMode.VIDEO -> binding.btnCapture.setImageResource(R.drawable.ic_video_record)
        }

        bindCameraUseCases()
    }

    private fun startTimeUpdater() {
        timeUpdateJob?.cancel()
        timeUpdateJob = lifecycleScope.launch {
            while (isActive) {
                binding.liveStampOverlay.tvTimestamp.text = currentLocationData.getFormattedDateTime()
                delay(1000L)
            }
        }
    }

    private fun updateLiveStampUI(loc: LocationData) {
        binding.liveStampOverlay.tvLocationTitle.text = loc.locationTitle
        binding.liveStampOverlay.tvAddressDetails.text = loc.fullAddress
        binding.liveStampOverlay.tvCoordinates.text = loc.formattedCoordinates
        binding.liveStampOverlay.tvTimestamp.text = loc.getFormattedDateTime()
    }

    private fun updateGpsStatusChip(loc: LocationData) {
        if (loc.hasGpsLock) {
            binding.ivGpsIndicator.setImageResource(R.drawable.ic_gps_fixed)
            val accText = String.format(getString(R.string.gps_locked), loc.displayAccuracy)
            binding.tvGpsStatus.text = accText
            binding.tvGpsStatus.setTextColor(ContextCompat.getColor(this, R.color.gps_locked))
        } else {
            binding.ivGpsIndicator.setImageResource(R.drawable.ic_gps_searching)
            binding.tvGpsStatus.text = getString(R.string.gps_searching)
            binding.tvGpsStatus.setTextColor(ContextCompat.getColor(this, R.color.gps_searching))
        }
    }

    private fun refreshMapPreview(lat: Double, lon: Double) {
        lifecycleScope.launch {
            val mapBmp = StaticMapHelper.getMapThumbnail(lat, lon, size = 260)
            currentMapBitmap = mapBmp
            binding.liveStampOverlay.ivMapTile.setImageBitmap(mapBmp)
        }
    }

    private fun cycleFlashMode() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_on)
                ImageCapture.FLASH_MODE_ON
            }
            ImageCapture.FLASH_MODE_ON -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_off)
                ImageCapture.FLASH_MODE_OFF
            }
            else -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_auto)
                ImageCapture.FLASH_MODE_AUTO
            }
        }
        imageCapture?.flashMode = flashMode
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            bindCameraUseCases()
            startLocation()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocation() {
        if (!locationHelper.isGpsEnabled()) {
            promptEnableGps()
        }
        locationHelper.startLocationUpdates()
    }

    private fun promptEnableGps() {
        AlertDialog.Builder(this)
            .setTitle("GPS Required")
            .setMessage("CamGPS requires device GPS to stamp accurate coordinates onto your photos. Please enable Location Services.")
            .setPositiveButton("Enable") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(getString(R.string.permission_required_message))
            .setPositiveButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun bindCameraUseCases() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            try {
                cameraProvider.unbindAll()

                // High-performance hardware Canvas overlay effect for burning GPS watermark into recorded video frames
                val overlayEffect = OverlayEffect(
                    CameraEffect.VIDEO_CAPTURE,
                    0,
                    Handler(Looper.getMainLooper())
                ) { _ ->
                }.apply {
                    setOnDrawListener { frame ->
                        val canvas = frame.overlayCanvas
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        val brand = if (currentMode == CameraMode.LIVE_PHOTO) "CamGPS • Live" else "CamGPS"
                        val rot = try { frame.rotationDegrees } catch (_: Exception) { if (canvas.width > canvas.height) 90 else 0 }
                        val isMirror = try { frame.isMirroring } catch (_: Exception) { false }
                        WatermarkDrawer.drawRotatedVideoWatermark(
                            canvas = canvas,
                            rotationDegrees = rot,
                            locationData = currentLocationData,
                            mapBitmap = currentMapBitmap,
                            brandTag = brand,
                            isMirroring = isMirror
                        )
                        true
                    }
                }

                when (currentMode) {
                    CameraMode.VIDEO -> {
                        val recorder = Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(Quality.HD))
                            .build()
                        videoCapture = VideoCapture.withOutput(recorder)

                        val useCaseGroup = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(videoCapture!!)
                            .addEffect(overlayEffect)
                            .build()

                        camera = cameraProvider.bindToLifecycle(
                            this,
                            cameraSelector,
                            useCaseGroup
                        )
                    }
                    CameraMode.LIVE_PHOTO -> {
                        imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .setFlashMode(flashMode)
                            .build()

                        val recorder = Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(Quality.HD))
                            .build()
                        videoCapture = VideoCapture.withOutput(recorder)

                        try {
                            val useCaseGroup = UseCaseGroup.Builder()
                                .addUseCase(preview)
                                .addUseCase(imageCapture!!)
                                .addUseCase(videoCapture!!)
                                .addEffect(overlayEffect)
                                .build()

                            camera = cameraProvider.bindToLifecycle(
                                this,
                                cameraSelector,
                                useCaseGroup
                            )
                        } catch (_: Exception) {
                            videoCapture = null
                            camera = cameraProvider.bindToLifecycle(
                                this,
                                cameraSelector,
                                preview,
                                imageCapture!!
                            )
                        }
                    }
                    CameraMode.PHOTO -> {
                        imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .setFlashMode(flashMode)
                            .build()
                        videoCapture = null

                        camera = cameraProvider.bindToLifecycle(
                            this,
                            cameraSelector,
                            preview,
                            imageCapture!!
                        )
                    }
                }
            } catch (exc: Exception) {
                Toast.makeText(this, "Camera error: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ================= PHOTO / LIVE PHOTO CAPTURE =================

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        animateShutter()
        binding.tvLoadingMessage.text = getString(R.string.capturing)
        binding.loadingOverlay.visibility = View.VISIBLE

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    processAndStampCapturedImage(imageProxy, isLivePhoto = false)
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        binding.loadingOverlay.visibility = View.GONE
                        Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun takeLivePhoto() {
        val imageCapture = this.imageCapture ?: return

        animateShutter()
        binding.tvLoadingMessage.text = "Capturing Live Photo..."
        binding.loadingOverlay.visibility = View.VISIBLE

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tempClipFile = File(cacheDir, "live_clip_$timestamp.mp4")

        val bitmapDeferred = CompletableDeferred<Bitmap?>()
        val videoDeferred = CompletableDeferred<Boolean>()

        // 1. Capture high-res still photo
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val baseBitmap = imageProxyToBitmap(imageProxy)
                        imageProxy.close()
                        bitmapDeferred.complete(baseBitmap)
                    } catch (e: Exception) {
                        imageProxy.close()
                        bitmapDeferred.complete(null)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    bitmapDeferred.complete(null)
                }
            }
        )

        // 2. Concurrently record short 2-second motion video clip with watermark to temporary file
        val videoCapture = this.videoCapture
        if (videoCapture != null && currentRecording == null) {
            val fileOutputOptions = FileOutputOptions.Builder(tempClipFile).build()
            val pendingRecording = videoCapture.output.prepareRecording(this, fileOutputOptions)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pendingRecording.withAudioEnabled()
            }

            var liveClipRecording: Recording? = null
            liveClipRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                if (recordEvent is VideoRecordEvent.Finalize) {
                    videoDeferred.complete(!recordEvent.hasError())
                }
            }

            // Record 2 seconds of live motion
            lifecycleScope.launch {
                delay(2000L)
                try {
                    liveClipRecording?.stop()
                } catch (_: Exception) {}
            }
        } else {
            videoDeferred.complete(false)
        }

        // 3. Assemble and save the genuine Google/Samsung Motion Photo format
        lifecycleScope.launch(Dispatchers.Default) {
            val baseBitmap = bitmapDeferred.await()
            val videoSuccess = videoDeferred.await()

            if (baseBitmap != null) {
                val mapBmp = currentMapBitmap ?: StaticMapHelper.getMapThumbnail(
                    currentLocationData.latitude,
                    currentLocationData.longitude,
                    size = 260
                )

                val stampedBitmap = WatermarkDrawer.stampPhoto(
                    sourceBitmap = baseBitmap,
                    locationData = currentLocationData,
                    mapBitmap = mapBmp,
                    isLivePhoto = true
                )

                val savedUri: Uri?
                if (videoSuccess && tempClipFile.exists() && tempClipFile.length() > 0) {
                    // Save as genuine Motion Photo (JPEG with XMP tags and appended MP4)
                    savedUri = MotionPhotoHelper.saveMotionPhoto(
                        context = this@MainActivity,
                        stampedBitmap = stampedBitmap,
                        videoFile = tempClipFile,
                        locationData = currentLocationData
                    )
                } else {
                    savedUri = StorageHelper.saveImageToGallery(this@MainActivity, stampedBitmap, "CamGPS_Live")
                }

                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    if (savedUri != null) {
                        Toast.makeText(this@MainActivity, "Live Photo saved with GPS stamp", Toast.LENGTH_SHORT).show()
                        val previewVideoUri = if (videoSuccess && tempClipFile.exists()) Uri.fromFile(tempClipFile) else null
                        showPhotoPreview(savedUri, previewVideoUri, isLive = true)
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.photo_save_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    Toast.makeText(this@MainActivity, getString(R.string.photo_save_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun animateShutter() {
        val flashView = View(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
        }
        binding.root.addView(flashView)
        flashView.animate()
            .alpha(0f)
            .setDuration(180L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.root.removeView(flashView)
                }
            })
    }

    private fun processAndStampCapturedImage(imageProxy: ImageProxy, isLivePhoto: Boolean) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                // 1. Convert ImageProxy to correctly rotated Bitmap
                val baseBitmap = imageProxyToBitmap(imageProxy)
                imageProxy.close()

                // 2. Prepare Map thumbnail centered exactly on GPS coordinates
                val mapBmp = currentMapBitmap ?: StaticMapHelper.getMapThumbnail(
                    currentLocationData.latitude,
                    currentLocationData.longitude,
                    size = 260
                )

                // 3. Stamp GPS watermark directly onto the Bitmap
                val stampedBitmap = WatermarkDrawer.stampPhoto(
                    sourceBitmap = baseBitmap,
                    locationData = currentLocationData,
                    mapBitmap = mapBmp,
                    isLivePhoto = isLivePhoto
                )

                // 4. Save to device Gallery (Pictures/CamGPS)
                val prefix = if (isLivePhoto) "CamGPS_Live" else "CamGPS"
                val savedUri = StorageHelper.saveImageToGallery(this@MainActivity, stampedBitmap, prefix)

                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    if (savedUri != null) {
                        Toast.makeText(this@MainActivity, getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
                        showPhotoPreview(savedUri, isLive = isLivePhoto)
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.photo_save_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Error processing photo: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                    postScale(-1f, 1f)
                }
            }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else {
            rawBitmap
        }
    }

    private fun showPhotoPreview(imageUri: Uri, videoUri: Uri? = null, isLive: Boolean = false) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        val dialogBinding = DialogPhotoPreviewBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.ivPreview.setImageURI(imageUri)
        dialogBinding.btnClose.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.btnShare.setOnClickListener {
            StorageHelper.sharePhoto(this, imageUri)
        }

        if (isLive && videoUri != null) {
            dialogBinding.btnLiveBadge.visibility = View.VISIBLE
            dialogBinding.tvPreviewTitle.text = "CamGPS Live Photo"

            fun playLiveMotion() {
                dialogBinding.videoPreview.apply {
                    visibility = View.VISIBLE
                    setVideoURI(videoUri)
                    setOnPreparedListener { mp ->
                        mp.isLooping = false
                        start()
                    }
                    setOnCompletionListener {
                        visibility = View.GONE
                    }
                }
            }

            dialogBinding.btnLiveBadge.setOnClickListener {
                playLiveMotion()
            }
            dialogBinding.ivPreview.setOnClickListener {
                playLiveMotion()
            }

            // Auto-play once on open for animated Live Photo feel
            lifecycleScope.launch {
                delay(300L)
                if (dialog.isShowing) {
                    playLiveMotion()
                }
            }
        } else {
            dialogBinding.btnLiveBadge.visibility = View.GONE
            dialogBinding.videoPreview.visibility = View.GONE
            dialogBinding.tvPreviewTitle.text = "CamGPS Capture"
        }

        dialog.setOnDismissListener {
            dialogBinding.videoPreview.stopPlayback()
        }

        dialog.show()
    }

    // ================= VIDEO RECORDING =================

    @SuppressLint("MissingPermission")
    private fun toggleVideoRecording() {
        if (currentRecording != null) {
            stopVideoRecording()
        } else {
            startVideoRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startVideoRecording() {
        val videoCapture = this.videoCapture ?: return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "CamGPS_Video_$timestamp.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (currentLocationData.hasGpsLock) {
                put(MediaStore.Video.Media.LATITUDE, currentLocationData.latitude)
                put(MediaStore.Video.Media.LONGITUDE, currentLocationData.longitude)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/CamGPS")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val pendingRecording = videoCapture.output.prepareRecording(this, mediaStoreOutput)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingRecording.withAudioEnabled()
        }

        currentRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    binding.btnCapture.setImageResource(R.drawable.ic_video_stop)
                    binding.layoutVideoRecording.visibility = View.VISIBLE
                    startVideoTimer()
                }
                is VideoRecordEvent.Finalize -> {
                    binding.btnCapture.setImageResource(R.drawable.ic_video_record)
                    binding.layoutVideoRecording.visibility = View.GONE
                    stopVideoTimer()
                    if (!recordEvent.hasError()) {
                        Toast.makeText(this, "Video saved to Movies/CamGPS", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Video recording error: ${recordEvent.error}", Toast.LENGTH_SHORT).show()
                    }
                    currentRecording = null
                }
            }
        }
    }

    private fun stopVideoRecording() {
        currentRecording?.stop()
        currentRecording = null
    }

    private fun startVideoTimer() {
        videoDurationSeconds = 0
        videoTimerJob?.cancel()
        videoTimerJob = lifecycleScope.launch {
            while (isActive) {
                val mins = videoDurationSeconds / 60
                val secs = videoDurationSeconds % 60
                binding.tvVideoTimer.text = String.format(Locale.US, "%02d:%02d", mins, secs)
                delay(1000L)
                videoDurationSeconds++
            }
        }
    }

    private fun stopVideoTimer() {
        videoTimerJob?.cancel()
        binding.tvVideoTimer.text = "00:00"
    }
}
