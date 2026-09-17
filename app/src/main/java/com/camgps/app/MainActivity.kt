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
import com.camgps.app.utils.StorageHelper
import com.camgps.app.watermark.StaticMapHelper
import com.camgps.app.watermark.WatermarkDrawer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class CameraMode {
    PORTRAIT,
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

        // Camera Modes: PORTRAIT, PHOTO, VIDEO
        binding.tvModePortrait.setOnClickListener { switchCameraMode(CameraMode.PORTRAIT) }
        binding.tvModePhoto.setOnClickListener { switchCameraMode(CameraMode.PHOTO) }
        binding.tvModeVideo.setOnClickListener { switchCameraMode(CameraMode.VIDEO) }

        // Shutter Button (Photo capture or Video record)
        binding.btnCapture.setOnClickListener {
            when (currentMode) {
                CameraMode.PHOTO, CameraMode.PORTRAIT -> takePhoto()
                CameraMode.VIDEO -> toggleVideoRecording()
            }
        }

        // GPS Info Button (Right side)
        binding.btnGpsInfo.setOnClickListener {
            if (!locationHelper.isGpsEnabled()) {
                promptEnableGps()
            } else {
                val acc = if (currentLocationData.hasGpsLock) {
                    "±${currentLocationData.accuracy.toInt()}m"
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
        binding.tvModePortrait.setTextColor(if (mode == CameraMode.PORTRAIT) Color.parseColor("#FFD600") else Color.parseColor("#88FFFFFF"))
        binding.tvModePhoto.setTextColor(if (mode == CameraMode.PHOTO) Color.parseColor("#FFD600") else Color.parseColor("#88FFFFFF"))
        binding.tvModeVideo.setTextColor(if (mode == CameraMode.VIDEO) Color.parseColor("#FFD600") else Color.parseColor("#88FFFFFF"))

        // Update shutter icon
        when (mode) {
            CameraMode.PHOTO -> binding.btnCapture.setImageResource(R.drawable.ic_shutter)
            CameraMode.PORTRAIT -> binding.btnCapture.setImageResource(R.drawable.ic_shutter_portrait)
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
            val accText = String.format(getString(R.string.gps_locked), loc.accuracy.toInt())
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

                if (currentMode == CameraMode.VIDEO) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)

                    camera = cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        videoCapture
                    )
                } else {
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setFlashMode(flashMode)
                        .build()

                    camera = cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                }
            } catch (exc: Exception) {
                Toast.makeText(this, "Camera error: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ================= PHOTO / PORTRAIT CAPTURE =================

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        animateShutter()
        binding.tvLoadingMessage.text = getString(R.string.capturing)
        binding.loadingOverlay.visibility = View.VISIBLE

        val isPortrait = (currentMode == CameraMode.PORTRAIT)

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    processAndStampCapturedImage(imageProxy, isPortrait)
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

    private fun processAndStampCapturedImage(imageProxy: ImageProxy, isPortrait: Boolean) {
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
                    isPortraitMode = isPortrait
                )

                // 4. Save to device Gallery (Pictures/CamGPS)
                val savedUri = StorageHelper.saveImageToGallery(this@MainActivity, stampedBitmap)

                withContext(Dispatchers.Main) {
                    binding.loadingOverlay.visibility = View.GONE
                    if (savedUri != null) {
                        Toast.makeText(this@MainActivity, getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
                        showPhotoPreview(savedUri)
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

    private fun showPhotoPreview(uri: Uri) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val dialogBinding = DialogPhotoPreviewBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.ivPreview.setImageURI(uri)
        dialogBinding.btnClose.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.btnShare.setOnClickListener {
            StorageHelper.sharePhoto(this, uri)
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
