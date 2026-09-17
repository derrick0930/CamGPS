package com.camgps.app.watermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asinh
import kotlin.math.tan

object StaticMapHelper {

    // In-memory tile cache to avoid repeated network downloads
    private val tileCache = LruCache<String, Bitmap>(64)

    /**
     * Obtains a satellite aerial map thumbnail bitmap.
     * Stitches satellite tiles so that the exact (latitude, longitude) is positioned
     * precisely at the center of the thumbnail, matching the real location seamlessly.
     */
    suspend fun getMapThumbnail(latitude: Double, longitude: Double, size: Int = 260): Bitmap =
        withContext(Dispatchers.IO) {
            val resultBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)

            // Zoom 18 gives crisp building-level detail suitable for GPS cameras
            val zoom = 18
            val scale = 256.0 * (1 shl zoom)

            // World coordinates of the exact GPS fix
            val centerWorldX = (longitude + 180.0) / 360.0 * scale
            val latRad = Math.toRadians(latitude)
            val centerWorldY = (1.0 - asinh(tan(latRad)) / Math.PI) / 2.0 * scale

            // Bounding box for the thumbnail in world pixel coordinates
            val minX = centerWorldX - (size / 2.0)
            val minY = centerWorldY - (size / 2.0)
            val maxX = centerWorldX + (size / 2.0)
            val maxY = centerWorldY + (size / 2.0)

            val tileX0 = (minX / 256.0).toInt()
            val tileX1 = (maxX / 256.0).toInt()
            val tileY0 = (minY / 256.0).toInt()
            val tileY1 = (maxY / 256.0).toInt()

            var downloadedAny = false
            val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            for (tx in tileX0..tileX1) {
                for (ty in tileY0..tileY1) {
                    val tileBmp = getSatelliteTile(tx, ty, zoom)
                    if (tileBmp != null) {
                        downloadedAny = true
                        val drawX = (tx * 256.0 - minX).toFloat()
                        val drawY = (ty * 256.0 - minY).toFloat()
                        canvas.drawBitmap(tileBmp, drawX, drawY, tilePaint)
                    }
                }
            }

            if (!downloadedAny) {
                // Offline fallback if network is completely off
                drawOfflineMapCanvas(canvas, size, latitude, longitude)
            }

            // Draw central Red 3D Location Pin - points precisely to (size/2, size/2)
            drawPinMarker(canvas, size / 2f, size / 2f, size * 0.17f)

            // Draw "Google" logo watermark at bottom-left, matching the reference photo
            drawGoogleAttribution(canvas, size)

            return@withContext resultBitmap
        }

    private fun getSatelliteTile(tx: Int, ty: Int, zoom: Int): Bitmap? {
        val cacheKey = "$zoom/$tx/$ty"
        tileCache.get(cacheKey)?.let { return it }

        // Primary: Google Satellite Hybrid (includes satellite photography + street/building labels)
        val googleUrl = "https://mt1.google.com/vt/lyrs=y&x=$tx&y=$ty&z=$zoom"
        var bmp = downloadTile(googleUrl)

        // Secondary fallback: Esri World Imagery (High-resolution global satellite photography)
        if (bmp == null) {
            val esriUrl = "https://services.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/tile/$zoom/$ty/$tx"
            bmp = downloadTile(esriUrl)
        }

        if (bmp != null) {
            tileCache.put(cacheKey, bmp)
        }
        return bmp
    }

    private fun downloadTile(urlString: String): Bitmap? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
            )
            connection.doInput = true
            connection.connect()

            if (connection.responseCode == 200) {
                val stream: InputStream = connection.inputStream
                val bmp = BitmapFactory.decodeStream(stream)
                stream.close()
                bmp
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun drawOfflineMapCanvas(canvas: Canvas, size: Int, lat: Double, lon: Double) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Dark terrain / satellite-like tone
        paint.color = Color.parseColor("#2C3539")
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        // Simulated street grid
        paint.color = Color.parseColor("#48535A")
        paint.strokeWidth = size * 0.05f
        paint.style = Paint.Style.STROKE

        canvas.drawLine(0f, size * 0.5f, size.toFloat(), size * 0.5f, paint)
        canvas.drawLine(size * 0.5f, 0f, size * 0.5f, size.toFloat(), paint)
        canvas.drawLine(0f, size * 0.25f, size.toFloat(), size * 0.25f, paint)
        canvas.drawLine(size * 0.75f, 0f, size * 0.75f, size.toFloat(), paint)

        paint.color = Color.parseColor("#E0E6ED")
        paint.strokeWidth = 2f
        canvas.drawLine(0f, size * 0.5f, size.toFloat(), size * 0.5f, paint)
        canvas.drawLine(size * 0.5f, 0f, size * 0.5f, size.toFloat(), paint)
    }

    private fun drawPinMarker(canvas: Canvas, cx: Float, cy: Float, pinHeight: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Pin Drop Shadow
        paint.color = Color.parseColor("#70000000")
        val shadowRadiusX = pinHeight * 0.28f
        val shadowRadiusY = pinHeight * 0.12f
        canvas.drawOval(
            RectF(cx - shadowRadiusX, cy - shadowRadiusY, cx + shadowRadiusX, cy + shadowRadiusY),
            paint
        )

        // The pin tip rests exactly at (cx, cy)
        val needleY = cy
        val radius = pinHeight * 0.32f
        val headCenterY = cy - pinHeight * 0.65f

        val pinPath = Path()
        pinPath.moveTo(cx, needleY)
        pinPath.cubicTo(
            cx - radius * 0.85f, cy - pinHeight * 0.35f,
            cx - radius, headCenterY + radius * 0.3f,
            cx - radius, headCenterY
        )
        pinPath.arcTo(
            RectF(cx - radius, headCenterY - radius, cx + radius, headCenterY + radius),
            180f, 180f, false
        )
        pinPath.cubicTo(
            cx + radius, headCenterY + radius * 0.3f,
            cx + radius * 0.85f, cy - pinHeight * 0.35f,
            cx, needleY
        )
        pinPath.close()

        paint.color = Color.parseColor("#E53935") // Google Red
        paint.style = Paint.Style.FILL
        canvas.drawPath(pinPath, paint)

        // Subtle darker contour on right side of pin for 3D depth
        paint.color = Color.parseColor("#20000000")
        canvas.drawCircle(cx + radius * 0.15f, headCenterY, radius * 0.9f, paint)

        // White Inner Circle in center of pin head
        paint.color = Color.WHITE
        canvas.drawCircle(cx, headCenterY, radius * 0.38f, paint)
    }

    private fun drawGoogleAttribution(canvas: Canvas, size: Int) {
        val text = "Google"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.09f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setShadowLayer(4f, 1.5f, 1.5f, Color.parseColor("#CC000000"))
        }
        val x = size * 0.05f
        val y = size * 0.92f
        canvas.drawText(text, x, y, paint)
    }
}
