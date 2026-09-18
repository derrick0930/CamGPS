package com.camgps.app.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.camgps.app.model.LocationData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Helper to build and save genuine Google/Android Motion Photos (Live Photos).
 * Encodes a primary JPEG stamped with GPS data, embeds standard Motion Photo XMP metadata,
 * and appends the MP4 micro-video stream directly to the file container.
 */
object MotionPhotoHelper {

    suspend fun saveMotionPhoto(
        context: Context,
        stampedBitmap: Bitmap,
        videoFile: File,
        locationData: LocationData
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val tempFile = File(context.cacheDir, "temp_live_$timestamp.jpg")

            // 1. Write base JPEG image
            FileOutputStream(tempFile).use { out ->
                stampedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.flush()
            }

            val videoLength = videoFile.length()

            // 2. Embed standard Motion Photo XMP metadata
            val xmpData = buildMotionPhotoXmp(videoLength)
            val exif = ExifInterface(tempFile.absolutePath)
            exif.setAttribute(ExifInterface.TAG_XMP, xmpData)

            // 3. Write GPS and DateTime EXIF tags
            if (locationData.hasGpsLock) {
                val lat = locationData.latitude
                val lon = locationData.longitude

                exif.setAttribute(
                    ExifInterface.TAG_GPS_LATITUDE,
                    decimalToDms(abs(lat))
                )
                exif.setAttribute(
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    if (lat >= 0) "N" else "S"
                )
                exif.setAttribute(
                    ExifInterface.TAG_GPS_LONGITUDE,
                    decimalToDms(abs(lon))
                )
                exif.setAttribute(
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    if (lon >= 0) "E" else "W"
                )
                exif.setAttribute(
                    ExifInterface.TAG_GPS_ALTITUDE,
                    "${abs(locationData.altitude).toInt()}/1"
                )
            }

            val dateStr = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
            exif.saveAttributes()

            // 4. Append the MP4 video bytes directly to the end of the JPEG
            FileOutputStream(tempFile, true).use { out ->
                FileInputStream(videoFile).use { inStream ->
                    inStream.copyTo(out)
                }
                out.flush()
            }

            // 5. Insert the Motion Photo into MediaStore Gallery
            val displayName = "CamGPS_Live_$timestamp.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (locationData.hasGpsLock) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        @Suppress("DEPRECATION")
                        put(MediaStore.Images.Media.LATITUDE, locationData.latitude)
                        @Suppress("DEPRECATION")
                        put(MediaStore.Images.Media.LONGITUDE, locationData.longitude)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CamGPS")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outStream ->
                    FileInputStream(tempFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                    outStream.flush()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                tempFile.delete()
                return@withContext uri
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    private fun buildMotionPhotoXmp(videoLength: Long): String {
        return """
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
        xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
        GCamera:MotionPhoto="1"
        Camera:MotionPhoto="1"
        GCamera:MotionPhotoVersion="1"
        Camera:MotionPhotoVersion="1"
        GCamera:MotionPhotoPresentationTimestampUs="1000000"
        Camera:MotionPhotoPresentationTimestampUs="1000000"
        GCamera:MicroVideo="1"
        Camera:MicroVideo="1"
        GCamera:MicroVideoVersion="1"
        Camera:MicroVideoVersion="1"
        GCamera:MicroVideoOffset="$videoLength"
        Camera:MicroVideoOffset="$videoLength"
        GCamera:MicroVideoPresentationTimestampUs="1000000"
        Camera:MicroVideoPresentationTimestampUs="1000000">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li rdf:parseType="Resource">
            <Item:Mime>image/jpeg</Item:Mime>
            <Item:Semantic>Primary</Item:Semantic>
            <Item:Length>0</Item:Length>
            <Item:Padding>0</Item:Padding>
          </rdf:li>
          <rdf:li rdf:parseType="Resource">
            <Item:Mime>video/mp4</Item:Mime>
            <Item:Semantic>MotionPhoto</Item:Semantic>
            <Item:Length>$videoLength</Item:Length>
            <Item:Padding>0</Item:Padding>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
        """.trimIndent()
    }

    private fun decimalToDms(decimal: Double): String {
        val d = decimal.toInt()
        val m = ((decimal - d) * 60).toInt()
        val s = ((decimal - d - m / 60.0) * 3600 * 1000).toInt()
        return "$d/1,$m/1,$s/1000"
    }
}
