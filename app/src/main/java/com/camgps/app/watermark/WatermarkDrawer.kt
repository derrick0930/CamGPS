package com.camgps.app.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.camgps.app.model.LocationData

object WatermarkDrawer {

    /**
     * Stretches or renders the watermark stamp onto the target captured Bitmap.
     */
    fun stampPhoto(
        sourceBitmap: Bitmap,
        locationData: LocationData,
        mapBitmap: Bitmap?,
        isLivePhoto: Boolean = false
    ): Bitmap {
        val workingBitmap = if (sourceBitmap.isMutable) sourceBitmap else sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(workingBitmap)
        val brandTag = if (isLivePhoto) "CamGPS • Live" else "CamGPS"

        drawWatermarkCard(
            canvas = canvas,
            imgWidth = workingBitmap.width.toFloat(),
            imgHeight = workingBitmap.height.toFloat(),
            locationData = locationData,
            mapBitmap = mapBitmap,
            brandTag = brandTag
        )

        return workingBitmap
    }

    /**
     * Renders the watermark onto a video frame Canvas, automatically accounting for
     * the video encoder buffer orientation (landscape sensor buffer) and output rotation metadata,
     * ensuring the watermark appears upright at the bottom of the portrait screen, identical to photo mode.
     */
    fun drawRotatedVideoWatermark(
        canvas: Canvas,
        rotationDegrees: Int,
        locationData: LocationData,
        mapBitmap: Bitmap?,
        brandTag: String = "CamGPS",
        isMirroring: Boolean = false
    ) {
        val bufW = canvas.width.toFloat()
        val bufH = canvas.height.toFloat()

        canvas.save()
        val portraitW: Float
        val portraitH: Float

        val normRotation = ((rotationDegrees % 360) + 360) % 360

        when {
            normRotation == 90 || (normRotation == 0 && bufW > bufH) -> {
                // Landscape buffer (e.g. 1920x1080) displayed as portrait (1080x1920) with +90° rotation.
                // Invert rotation: translate to (0, bufH) and rotate -90°
                canvas.translate(0f, bufH)
                canvas.rotate(-90f)
                portraitW = bufH
                portraitH = bufW
            }
            normRotation == 270 -> {
                // Front camera rotated 270°: translate to (bufW, 0) and rotate +90°
                canvas.translate(bufW, 0f)
                canvas.rotate(90f)
                portraitW = bufH
                portraitH = bufW
            }
            normRotation == 180 -> {
                canvas.translate(bufW, bufH)
                canvas.rotate(180f)
                portraitW = bufW
                portraitH = bufH
            }
            else -> {
                // Native portrait buffer
                portraitW = bufW
                portraitH = bufH
            }
        }

        if (isMirroring) {
            canvas.translate(portraitW, 0f)
            canvas.scale(-1f, 1f)
        }

        drawWatermarkCard(
            canvas = canvas,
            imgWidth = portraitW,
            imgHeight = portraitH,
            locationData = locationData,
            mapBitmap = mapBitmap,
            brandTag = brandTag
        )
        canvas.restore()
    }

    /**
     * Renders the complete GPS watermark card on any Canvas (used for photos and video frames).
     */
    fun drawWatermarkCard(
        canvas: Canvas,
        imgWidth: Float,
        imgHeight: Float,
        locationData: LocationData,
        mapBitmap: Bitmap?,
        brandTag: String = "CamGPS"
    ) {
        // Base reference width is 1080px; scale fonts and dimensions proportionally
        val scale = (imgWidth / 1080f).coerceIn(0.5f, 3.0f)

        // Padding & margins
        val cardMarginH = 24f * scale
        val cardMarginB = 32f * scale
        val cardPadding = 18f * scale
        val cornerRadius = 24f * scale

        // Card width
        val cardWidth = imgWidth - (cardMarginH * 2f)

        // Left Map size
        val mapSize = 220f * scale
        val mapCornerRadius = 14f * scale

        // Available text width (cardWidth - mapSize - paddings - gap)
        val textGap = 16f * scale
        val textWidth = (cardWidth - (cardPadding * 2f) - mapSize - textGap).toInt().coerceAtLeast(100)

        // Text paints
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(4f * scale, 1f * scale, 1f * scale, Color.parseColor("#90000000"))
        }

        val addressPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EEEEEE")
            textSize = 20f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(3f * scale, 1f * scale, 1f * scale, Color.parseColor("#80000000"))
        }

        val coordsPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(3f * scale, 1f * scale, 1f * scale, Color.parseColor("#90000000"))
        }

        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DCDCDC")
            textSize = 20f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(3f * scale, 1f * scale, 1f * scale, Color.parseColor("#80000000"))
        }

        // Layout the text sections
        val titleLayout = createStaticLayout(locationData.locationTitle, titlePaint, textWidth, maxLines = 2)
        val addressLayout = createStaticLayout(locationData.fullAddress, addressPaint, textWidth, maxLines = 3)
        val coordsLayout = createStaticLayout(locationData.formattedCoordinates, coordsPaint, textWidth, maxLines = 1)
        val timeLayout = createStaticLayout(locationData.getFormattedDateTime(), timePaint, textWidth, maxLines = 1)

        val textSpacing = 6f * scale
        val totalTextHeight = titleLayout.height + textSpacing +
                addressLayout.height + textSpacing +
                coordsLayout.height + textSpacing +
                timeLayout.height

        // Total card height accommodates both map and text
        val contentHeight = maxOf(mapSize, totalTextHeight)
        val cardHeight = contentHeight + (cardPadding * 2f)

        val cardLeft = cardMarginH
        val cardTop = imgHeight - cardMarginB - cardHeight
        val cardRight = cardLeft + cardWidth
        val cardBottom = cardTop + cardHeight

        // 1. Draw Card Background (Semi-transparent black with subtle border)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC000000") // 80% opacity dark overlay
            style = Paint.Style.FILL
        }
        val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#25FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * scale
        }
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)

        // 2. Draw Mini Map with rounded corners
        val mapLeft = cardLeft + cardPadding
        val mapTop = cardTop + cardPadding + ((contentHeight - mapSize) / 2f)
        val mapRight = mapLeft + mapSize
        val mapBottom = mapTop + mapSize
        val mapRect = RectF(mapLeft, mapTop, mapRight, mapBottom)

        if (mapBitmap != null && !mapBitmap.isRecycled) {
            canvas.save()
            val clipPath = android.graphics.Path().apply {
                addRoundRect(mapRect, mapCornerRadius, mapCornerRadius, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            val srcMapRect = Rect(0, 0, mapBitmap.width, mapBitmap.height)
            canvas.drawBitmap(mapBitmap, srcMapRect, mapRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            // Placeholder map card background
            val mapBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1A202C")
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(mapRect, mapCornerRadius, mapCornerRadius, mapBgPaint)
        }

        // 3. Draw Watermark Badge at Top-Right (e.g. "CamGPS" or "CamGPS • Live")
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DDDDDD")
            textSize = 18f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val brandTagWidth = brandPaint.measureText(brandTag) + 16f * scale
        val brandTagHeight = 26f * scale
        val brandTagRight = cardRight - cardPadding
        val brandTagTop = cardTop + cardPadding
        val brandTagLeft = brandTagRight - brandTagWidth
        val brandTagBottom = brandTagTop + brandTagHeight

        val brandBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#55000000")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(brandTagLeft, brandTagTop, brandTagRight, brandTagBottom),
            6f * scale, 6f * scale, brandBgPaint
        )
        canvas.drawText(
            brandTag,
            brandTagLeft + 8f * scale,
            brandTagTop + 18f * scale,
            brandPaint
        )

        // 4. Draw Text Sections
        val textLeft = mapRight + textGap
        var currentY = cardTop + cardPadding

        // Title
        canvas.save()
        canvas.translate(textLeft, currentY)
        titleLayout.draw(canvas)
        canvas.restore()
        currentY += titleLayout.height + textSpacing

        // Address
        canvas.save()
        canvas.translate(textLeft, currentY)
        addressLayout.draw(canvas)
        canvas.restore()
        currentY += addressLayout.height + textSpacing

        // Coordinates
        canvas.save()
        canvas.translate(textLeft, currentY)
        coordsLayout.draw(canvas)
        canvas.restore()
        currentY += coordsLayout.height + textSpacing

        // Timestamp
        canvas.save()
        canvas.translate(textLeft, currentY)
        timeLayout.draw(canvas)
        canvas.restore()
    }

    private fun createStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        maxLines: Int
    ): StaticLayout {
        val safeWidth = maxOf(width, 10)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1f)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text,
                paint,
                safeWidth,
                Layout.Alignment.ALIGN_NORMAL,
                1f,
                2f,
                false
            )
        }
    }
}
