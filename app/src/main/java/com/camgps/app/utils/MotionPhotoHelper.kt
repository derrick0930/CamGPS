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
 *
 * The standard structure of an Android Motion Photo JPEG is:
 *   [SOI (0xFF 0xD8)] [APP0 / EXIF APP1] [XMP APP1 segment] [rest of JPEG] [EOI (0xFF 0xD9)] [MP4 bytes]
 *
 * Requirements:
 * 1. The XMP MUST be written as a standalone APP1 segment (marker 0xFF 0xE1 + header "http://ns.adobe.com/xap/1.0/\0").
 * 2. It must be placed AFTER the EXIF APP1 segment to preserve JPEG/EXIF spec compliance.
 * 3. It must contain BOTH modern GContainer (Container:Directory with Item:Length) and
 *    legacy MicroVideo tags (GCamera:MicroVideo="1", GCamera:MicroVideoOffset) for maximum
 *    compatibility across Google Photos, Samsung Gallery, and other Android gallery apps.
 * 4. Item:Length and GCamera:MicroVideoOffset must hold the exact byte length of the appended MP4.
 */
object MotionPhotoHelper {

    /** Standard XMP standalone APP1 namespace header (null-terminated, per XMP spec) */
    private val XMP_APP1_HEADER: ByteArray =
        "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)

    suspend fun saveMotionPhoto(
        context: Context,
        stampedBitmap: Bitmap,
        videoFile: File,
        locationData: LocationData
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            // Temporary plain JPEG (before XMP injection)
            val tempJpegFile = File(context.cacheDir, "temp_live_jpeg_$timestamp.jpg")
            // Final assembled Motion Photo file
            val tempMotionFile = File(context.cacheDir, "temp_live_$timestamp.jpg")

            // ── Step 1: Write base JPEG ──────────────────────────────────────────────
            FileOutputStream(tempJpegFile).use { out ->
                stampedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.flush()
            }

            // ── Step 2: Write GPS + DateTime EXIF only (NO TAG_XMP here) ────────────
            //   ExifInterface.TAG_XMP buries XMP in EXIF IFD tag 700; gallery apps
            //   look for XMP in a standalone APP1 segment — so we inject it manually.
            val exif = ExifInterface(tempJpegFile.absolutePath)
            if (locationData.hasGpsLock) {
                val lat = locationData.latitude
                val lon = locationData.longitude
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, decimalToDms(abs(lat)))
                exif.setAttribute(
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    if (lat >= 0) "N" else "S"
                )
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, decimalToDms(abs(lon)))
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
            exif.saveAttributes()  // rewrites JPEG in-place with EXIF only

            // ── Step 3: Build XMP using exact video byte length ──────────────────────
            val videoLength = videoFile.length()
            val xmpBytes = buildMotionPhotoXmp(videoLength).toByteArray(Charsets.UTF_8)

            // ── Step 4: Assemble final Motion Photo file ─────────────────────────────
            //   Layout: [SOI + EXIF APP1] [XMP APP1 segment] [rest of JPEG] [MP4 bytes]
            val jpegBytes = tempJpegFile.readBytes()
            val insertionOffset = findInsertionOffset(jpegBytes)

            FileOutputStream(tempMotionFile).use { out ->
                // 4a. Write JPEG up to the insertion point (SOI and APP0 / EXIF APP1)
                out.write(jpegBytes, 0, insertionOffset)

                // 4b. Write standalone XMP APP1 segment
                //   Length field = 2 (the length field itself) + header size + XMP size
                val segmentLen = 2 + XMP_APP1_HEADER.size + xmpBytes.size
                out.write(0xFF)                           // APP1 marker
                out.write(0xE1)
                out.write((segmentLen shr 8) and 0xFF)   // length high byte
                out.write(segmentLen and 0xFF)            // length low byte
                out.write(XMP_APP1_HEADER)               // "http://ns.adobe.com/xap/1.0/\0"
                out.write(xmpBytes)                       // XMP packet data

                // 4c. Write remaining JPEG bytes (from insertionOffset through EOI 0xFF 0xD9)
                out.write(jpegBytes, insertionOffset, jpegBytes.size - insertionOffset)

                // 4d. Append the MP4 micro-video immediately after JPEG EOI
                FileInputStream(videoFile).use { videoIn ->
                    videoIn.copyTo(out)
                }
                out.flush()
            }

            tempJpegFile.delete()

            // ── Step 5: Insert the Motion Photo into MediaStore Gallery ───────────────
            // Use .MP.jpg (standard modern Google Camera / Motion Photo naming pattern)
            val displayName = "CamGPS_Live_$timestamp.MP.jpg"
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
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/CamGPS"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outStream ->
                    FileInputStream(tempMotionFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                    outStream.flush()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                tempMotionFile.delete()
                return@withContext uri
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    /**
     * Finds the offset in [jpegBytes] right after the EXIF APP1 segment (or APP0 JFIF segment).
     * This ensures the XMP APP1 segment is inserted immediately after EXIF, conforming to the
     * JPEG metadata standard and keeping EXIF at the required position after SOI.
     */
    private fun findInsertionOffset(jpegBytes: ByteArray): Int {
        if (jpegBytes.size < 4 || jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) {
            return 2 // fallback: right after SOI
        }
        var offset = 2
        while (offset + 4 <= jpegBytes.size) {
            // Marker must begin with 0xFF
            if (jpegBytes[offset] != 0xFF.toByte()) {
                break
            }
            val marker = jpegBytes[offset + 1].toInt() and 0xFF
            // Standalone markers without length
            if (marker == 0xD8 || marker == 0xD9 || (marker in 0xD0..0xD7)) {
                offset += 2
                continue
            }
            // If we hit SOS (0xDA), we've reached image scan data
            if (marker == 0xDA) {
                return offset
            }

            val segmentLen = ((jpegBytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (jpegBytes[offset + 3].toInt() and 0xFF)
            if (segmentLen < 2) break
            val nextOffset = offset + 2 + segmentLen
            if (nextOffset > jpegBytes.size) break

            // Check if this is an EXIF APP1 segment: starts with "Exif\0\0"
            if (marker == 0xE1) {
                if (offset + 10 <= jpegBytes.size &&
                    jpegBytes[offset + 4] == 'E'.code.toByte() &&
                    jpegBytes[offset + 5] == 'x'.code.toByte() &&
                    jpegBytes[offset + 6] == 'i'.code.toByte() &&
                    jpegBytes[offset + 7] == 'f'.code.toByte() &&
                    jpegBytes[offset + 8] == 0.toByte() &&
                    jpegBytes[offset + 9] == 0.toByte()
                ) {
                    // Found EXIF! Insert XMP right after this segment
                    return nextOffset
                }
                // Non-Exif APP1
                offset = nextOffset
            } else if (marker == 0xE0) {
                // APP0 (JFIF) - keep going to find EXIF APP1
                offset = nextOffset
            } else {
                // First non-APP marker (e.g. DQT 0xDB) before finding EXIF
                return offset
            }
        }
        return 2 // Fallback: right after SOI
    }

    /**
     * Builds a valid XMP packet containing both modern Container:Directory and
     * legacy MicroVideo tags, ensuring maximum compatibility across Google Photos,
     * Samsung Gallery, Xiaomi Gallery, and Android Media3 / ExoPlayer.
     */
    private fun buildMotionPhotoXmp(videoLength: Long): String {
        return """<?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
        GCamera:MotionPhoto="1"
        GCamera:MotionPhotoVersion="1"
        GCamera:MotionPhotoPresentationTimestampUs="1000000"
        GCamera:MicroVideo="1"
        GCamera:MicroVideoVersion="1"
        GCamera:MicroVideoOffset="$videoLength"
        GCamera:MicroVideoPresentationTimestampUs="1000000">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
              Item:Mime="image/jpeg"
              Item:Semantic="Primary"
              Item:Length="0"
              Item:Padding="0"/>
          </rdf:li>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
              Item:Mime="video/mp4"
              Item:Semantic="MotionPhoto"
              Item:Length="$videoLength"
              Item:Padding="0"/>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    private fun decimalToDms(decimal: Double): String {
        val d = decimal.toInt()
        val m = ((decimal - d) * 60).toInt()
        val s = ((decimal - d - m / 60.0) * 3600 * 1000).toInt()
        return "$d/1,$m/1,$s/1000"
    }
}
