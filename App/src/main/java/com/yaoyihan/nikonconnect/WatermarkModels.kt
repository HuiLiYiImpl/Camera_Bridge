package com.yaoyihan.nikonconnect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.text.TextPaint
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class WatermarkLayout(val title: String) {
    MINIMAL("氛围模糊背景"),
    LEFT_PARAMS("底部信息条（Logo 左）"),
    RIGHT_PARAMS("底部信息条（Logo 右）"),
    WHITE_BORDER("正方形白边框"),
    CUSTOM("仅自定义文字"),
}

enum class WatermarkField(val title: String) {
    CAMERA_BRAND("相机品牌"),
    CAMERA_MODEL("相机型号"),
    LENS_MODEL("镜头型号"),
    ISO("ISO"),
    SHUTTER("快门速度"),
    APERTURE("光圈"),
    FOCAL_LENGTH("焦距"),
    EQUIVALENT_FOCAL_LENGTH("等效焦距"),
    CAPTURE_DATE("拍摄日期"),
    CUSTOM_TEXT("自定义文字"),
    COPYRIGHT("版权文字"),
}

enum class WatermarkLogoPosition(val title: String) {
    TOP_LEFT("左侧"),
    TOP_RIGHT("右侧"),
}

fun WatermarkLayout.uiTitle(): String = title

data class WatermarkPreset(
    val id: String,
    val name: String,
    val layout: WatermarkLayout = WatermarkLayout.MINIMAL,
    val fields: Set<WatermarkField> = setOf(WatermarkField.CAMERA_BRAND, WatermarkField.CAMERA_MODEL, WatermarkField.CAPTURE_DATE),
    val fontSize: Int = 34,
    val textColor: Int = Color.WHITE,
    val backgroundColor: Int = Color.BLACK,
    val backgroundAlpha: Int = 82,
    val margin: Int = 28,
    val showBorder: Boolean = false,
    val frameEnabled: Boolean = false,
    val frameThickness: Int = 24,
    val customText: String = "",
    val copyrightText: String = "",
    val logoEnabled: Boolean = false,
    val useBrandLogo: Boolean = false,
    val logoUri: String? = null,
    val logoScale: Int = 100,
    val logoAlpha: Int = 100,
    val logoPosition: WatermarkLogoPosition = WatermarkLogoPosition.TOP_RIGHT,
    val quality: Int = 95,
)

data class PhotoMetadata(
    val cameraBrand: String? = null,
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val iso: Int? = null,
    val shutterSpeed: String? = null,
    val aperture: String? = null,
    val focalLength: String? = null,
    val equivalentFocalLength: String? = null,
    val capturedAt: Date? = null,
    val orientation: Int? = null,
    val customText: String? = null,
    val copyrightText: String? = null,
)

data class LoadedPhoto(val bitmap: Bitmap, val metadata: PhotoMetadata)

object ExifMetadataReader {
    fun fromBytes(bytes: ByteArray, fallback: PhotoMetadata = PhotoMetadata()): PhotoMetadata = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).toMetadata(fallback)
    }.getOrDefault(fallback)

    fun fromUri(context: Context, uri: Uri, fallback: PhotoMetadata = PhotoMetadata()): PhotoMetadata = runCatching {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).toMetadata(fallback) } ?: fallback
    }.getOrDefault(fallback)

    private fun ExifInterface.toMetadata(fallback: PhotoMetadata): PhotoMetadata {
        val make = getAttribute(ExifInterface.TAG_MAKE).clean()
        val model = getAttribute(ExifInterface.TAG_MODEL).clean()
        val lens = getAttribute("LensModel").clean()
        val iso = getAttributeInt("PhotographicSensitivity", 0).takeIf { it > 0 }
            ?: getAttributeInt("ISOSpeedRatings", 0).takeIf { it > 0 }
        val exposure = getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { value ->
            if (value < 1.0) "1/${(1.0 / value).roundToInt()}s" else "${"%.2f".format(Locale.US, value)}s"
        }
        val fNumber = getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }
            ?.let { "f/${"%.1f".format(Locale.US, it)}" }
        val focal = getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }
            ?.let { "${it.roundToInt()}mm" }
        val equivalent = getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 }
            ?.let { "${it}mm" }
        val date = getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.parseExifDate()
            ?: getAttribute(ExifInterface.TAG_DATETIME)?.parseExifDate()
        val orientation = getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            .takeIf { it != ExifInterface.ORIENTATION_UNDEFINED }
        return PhotoMetadata(
            cameraBrand = make ?: fallback.cameraBrand,
            cameraModel = model ?: fallback.cameraModel,
            lensModel = lens ?: fallback.lensModel,
            iso = iso ?: fallback.iso,
            shutterSpeed = exposure ?: fallback.shutterSpeed,
            aperture = fNumber ?: fallback.aperture,
            focalLength = focal ?: fallback.focalLength,
            equivalentFocalLength = equivalent ?: fallback.equivalentFocalLength,
            capturedAt = date ?: fallback.capturedAt,
            orientation = orientation ?: fallback.orientation,
            customText = fallback.customText,
            copyrightText = getAttribute(ExifInterface.TAG_COPYRIGHT).clean() ?: fallback.copyrightText,
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf {
        it.isNotBlank() && !it.equals("unknown", true) && it != "0mm" && it != "0"
    }

    private fun String.parseExifDate(): Date? = runCatching {
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(this)
    }.getOrNull()
}

object WatermarkRenderer {
    private const val MAX_ATMOSPHERE_EDGE = 6000
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    fun render(source: Bitmap, metadata: PhotoMetadata, preset: WatermarkPreset, context: Context? = null): Bitmap = when (preset.layout) {
        WatermarkLayout.MINIMAL -> renderAtmosphere(source, metadata, preset, context)
        WatermarkLayout.RIGHT_PARAMS -> renderInfoStrip(source, metadata, preset, context, logoOnRight = true)
        WatermarkLayout.LEFT_PARAMS -> renderInfoStrip(source, metadata, preset, context, logoOnRight = false)
        WatermarkLayout.WHITE_BORDER -> renderSquare(source)
        WatermarkLayout.CUSTOM -> renderCustom(source, metadata, preset, context)
    }

    private fun renderAtmosphere(source: Bitmap, metadata: PhotoMetadata, preset: WatermarkPreset, context: Context?): Bitmap {
        val rawWidth = source.width
        val rawHeight = max((rawWidth * 1.25f).roundToInt(), source.height + (rawWidth * .28f).roundToInt())
        val outputScale = min(1f, MAX_ATMOSPHERE_EDGE.toFloat() / max(rawWidth, rawHeight))
        val width = max(1, (rawWidth * outputScale).roundToInt())
        val height = max(1, (rawHeight * outputScale).roundToInt())
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val tinyScale = min(1f, 160f / max(source.width, source.height))
        val tiny = Bitmap.createScaledBitmap(
            source,
            max(1, (source.width * tinyScale).roundToInt()),
            max(1, (source.height * tinyScale).roundToInt()),
            true,
        )
        canvas.drawBitmap(
            tiny,
            centerCropSource(tiny.width, tiny.height, width.toFloat(), height.toFloat(), 1.11f),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            imagePaint,
        )
        if (tiny !== source) tiny.recycle()
        canvas.drawColor(Color.argb(preset.backgroundAlpha.coerceIn(64, 102), 0, 0, 0))

        val side = width * (.05f + preset.margin.coerceIn(12, 64) / 1080f)
        val top = height * .035f
        val imageAreaBottom = height - max(height * .16f, width * .20f)
        val photoRect = fitCenterRect(
            source.width,
            source.height,
            RectF(side, top, width - side, imageAreaBottom),
        )
        val radius = width * .03f
        val clip = Path().apply { addRoundRect(photoRect, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(source, null, photoRect, imagePaint)
        canvas.restore()

        val camera = metadata.cameraLabel(preset)
        val parameters = metadata.shootingLabel(preset, " ")
        val caption = metadata.captionLabel(preset)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = preset.textColor
            textAlign = Paint.Align.CENTER
            textSize = max(24f, width * .036f * preset.fontSize / 34f)
            typeface = watermarkTypeface(context, true)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(preset.textColor, 220)
            textAlign = Paint.Align.CENTER
            textSize = max(18f, width * .025f * preset.fontSize / 34f)
            typeface = watermarkTypeface(context, false)
        }
        val textTop = photoRect.bottom + max(height * .025f, width * .025f)
        val center = width / 2f
        var baseline = textTop + titlePaint.textSize
        if (camera != null) {
            canvas.drawText(ellipsize(camera, titlePaint, width * .84f), center, baseline, titlePaint)
            baseline += bodyPaint.textSize * 1.65f
        }
        if (parameters != null) {
            canvas.drawText(ellipsize(parameters, bodyPaint, width * .86f), center, baseline, bodyPaint)
            baseline += bodyPaint.textSize * 1.45f
        }
        if (caption != null && baseline < height - bodyPaint.textSize * .3f) {
            canvas.drawText(ellipsize(caption, bodyPaint, width * .86f), center, baseline, bodyPaint)
        }
        return output
    }

    private fun renderInfoStrip(
        source: Bitmap,
        metadata: PhotoMetadata,
        preset: WatermarkPreset,
        context: Context?,
        logoOnRight: Boolean,
    ): Bitmap {
        val scale = source.width / 1080f
        val frame = if (preset.frameEnabled) max(1, (preset.frameThickness * scale).roundToInt()) else 0
        val stripHeight = max(48, (source.width * .13f).roundToInt())
        val width = source.width + frame * 2
        val height = source.height + stripHeight + frame * 2
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, frame.toFloat(), frame.toFloat(), imagePaint)

        val stripTop = (frame + source.height).toFloat()
        val imageLeft = frame.toFloat()
        val imageRight = (frame + source.width).toFloat()
        val padding = max(source.width * .015f, preset.margin * scale)
        val contentLeft = imageLeft + padding
        val contentRight = imageRight - padding
        val stripBottom = stripTop + stripHeight
        canvas.drawLine(imageLeft, stripTop, imageRight, stripTop, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(232, 232, 232)
            strokeWidth = max(1f, scale)
        })

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 24, 24)
            textSize = max(16f, source.width * .024f * preset.fontSize / 34f)
            typeface = watermarkTypeface(context, true)
        }
        val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(125, 125, 125)
            textSize = max(13f, source.width * .019f * preset.fontSize / 34f)
            typeface = watermarkTypeface(context, false)
        }
        val parameterPaint = TextPaint(titlePaint).apply { textSize = max(15f, source.width * .0215f * preset.fontSize / 34f) }
        val paramsWidth = source.width * .32f
        val paramsLeft = contentRight - paramsWidth
        val hasBuiltInLogo = context != null && preset.useBrandLogo && brandLogoResource(context, metadata.cameraBrand) != 0
        val hasLogoSlot = preset.logoEnabled && (!preset.logoUri.isNullOrBlank() || hasBuiltInLogo || (WatermarkField.CAMERA_BRAND in preset.fields && metadata.cameraBrand.cleanDisplay() != null))
        val logoSlotWidth = if (hasLogoSlot) source.width * .09f else 0f
        val gap = source.width * .016f
        val lens = metadata.lensLabel(preset) ?: metadata.cameraLabel(preset) ?: metadata.captionLabel(preset)
        val camera = metadata.secondaryInfoLabel(preset, lens)
        val parameters = metadata.shootingLabel(preset, " · ")
        val date = metadata.dateLabel(preset)

        if (logoOnRight) {
            val logoRight = paramsLeft - gap
            val logoLeft = logoRight - logoSlotWidth
            drawTwoLines(canvas, lens, camera, contentLeft, if (hasLogoSlot) logoLeft - gap else paramsLeft - gap, stripTop, stripBottom, titlePaint, detailPaint)
            if (hasLogoSlot) {
                drawLogoOrBrand(canvas, context, preset, metadata, RectF(logoLeft, stripTop + stripHeight * .18f, logoRight, stripBottom - stripHeight * .18f), titlePaint)
                drawDivider(canvas, paramsLeft - gap * .45f, stripTop, stripBottom, scale)
            }
        } else {
            val logoLeft = contentLeft
            val logoRight = logoLeft + logoSlotWidth
            if (hasLogoSlot) {
                drawLogoOrBrand(canvas, context, preset, metadata, RectF(logoLeft, stripTop + stripHeight * .16f, logoRight, stripBottom - stripHeight * .16f), titlePaint)
                drawDivider(canvas, logoRight + gap * .45f, stripTop, stripBottom, scale)
            }
            drawTwoLines(canvas, lens, camera, if (hasLogoSlot) logoRight + gap * 1.4f else contentLeft, paramsLeft - gap, stripTop, stripBottom, titlePaint, detailPaint)
        }
        drawTwoLines(canvas, parameters, date, paramsLeft, contentRight, stripTop, stripBottom, parameterPaint, detailPaint)
        return output
    }

    private fun renderSquare(source: Bitmap): Bitmap {
        val side = max(source.width, source.height)
        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, ((side - source.width) / 2f), ((side - source.height) / 2f), imagePaint)
        return output
    }

    private fun renderCustom(source: Bitmap, metadata: PhotoMetadata, preset: WatermarkPreset, context: Context?): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(source, 0f, 0f, imagePaint)
        val text = metadata.captionLabel(preset) ?: return output
        val scale = source.width / 1080f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = preset.textColor
            textSize = max(18f, preset.fontSize * scale)
            typeface = watermarkTypeface(context, true)
        }
        val padding = max(12f, preset.margin * scale)
        val fitted = ellipsize(text, paint, source.width - padding * 4)
        val width = paint.measureText(fitted) + padding * 2
        val height = paint.textSize + padding * 1.6f
        val rect = RectF(padding, source.height - height - padding, min(source.width - padding, padding + width), source.height - padding)
        canvas.drawRoundRect(rect, 12f * scale, 12f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(preset.backgroundAlpha.coerceIn(0, 255), Color.red(preset.backgroundColor), Color.green(preset.backgroundColor), Color.blue(preset.backgroundColor))
        })
        canvas.drawText(fitted, rect.left + padding, rect.bottom - padding * .65f, paint)
        return output
    }

    private fun drawLogoOrBrand(
        canvas: Canvas,
        context: Context?,
        preset: WatermarkPreset,
        metadata: PhotoMetadata,
        bounds: RectF,
        textPaint: TextPaint,
    ) {
        if (!preset.logoEnabled) return
        val logo = if (context != null) runCatching {
            when {
                !preset.logoUri.isNullOrBlank() -> context.contentResolver.openInputStream(Uri.parse(preset.logoUri))?.use(BitmapFactory::decodeStream)
                preset.useBrandLogo -> brandLogoResource(context, metadata.cameraBrand).takeIf { it != 0 }?.let { BitmapFactory.decodeResource(context.resources, it) }
                else -> null
            }
        }.getOrNull() else null
        if (logo != null) {
            val scaledBounds = RectF(bounds).apply {
                val factor = (preset.logoScale / 100f).coerceIn(.5f, 1.8f)
                val cx = centerX()
                val cy = centerY()
                val halfWidth = width() * factor / 2f
                val halfHeight = height() * factor / 2f
                set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
            }
            val destination = fitCenterRect(logo.width, logo.height, scaledBounds)
            canvas.drawBitmap(logo, null, destination, Paint(imagePaint).apply {
                alpha = (255 * preset.logoAlpha.coerceIn(10, 100) / 100f).roundToInt()
            })
            logo.recycle()
            return
        }
        val brand = metadata.cameraBrand.cleanDisplay()
            ?.takeIf { WatermarkField.CAMERA_BRAND in preset.fields }
            ?: return
        val paint = TextPaint(textPaint).apply {
            color = Color.rgb(38, 38, 38)
            textAlign = Paint.Align.CENTER
            textSize = min(textSize, bounds.height() * .34f)
        }
        canvas.drawText(ellipsize(brand, paint, bounds.width()), bounds.centerX(), bounds.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun drawTwoLines(
        canvas: Canvas,
        first: String?,
        second: String?,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        firstPaint: TextPaint,
        secondPaint: TextPaint,
    ) {
        if (right <= left || (first == null && second == null)) return
        val available = right - left
        val center = (top + bottom) / 2f
        if (first != null && second != null) {
            canvas.drawText(ellipsize(first, firstPaint, available), left, center - firstPaint.descent() - firstPaint.textSize * .12f, firstPaint)
            canvas.drawText(ellipsize(second, secondPaint, available), left, center - secondPaint.ascent() + secondPaint.textSize * .12f, secondPaint)
        } else {
            val text = first ?: second ?: return
            val paint = if (first != null) firstPaint else secondPaint
            canvas.drawText(ellipsize(text, paint, available), left, center - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }

    private fun drawDivider(canvas: Canvas, x: Float, top: Float, bottom: Float, scale: Float) {
        canvas.drawLine(x, top + (bottom - top) * .22f, x, bottom - (bottom - top) * .22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 205, 205)
            strokeWidth = max(1f, scale)
        })
    }

    private fun fitCenterRect(sourceWidth: Int, sourceHeight: Int, bounds: RectF): RectF {
        val factor = min(bounds.width() / sourceWidth, bounds.height() / sourceHeight)
        val width = sourceWidth * factor
        val height = sourceHeight * factor
        return RectF(
            bounds.centerX() - width / 2f,
            bounds.centerY() - height / 2f,
            bounds.centerX() + width / 2f,
            bounds.centerY() + height / 2f,
        )
    }

    private fun centerCropSource(sourceWidth: Int, sourceHeight: Int, targetWidth: Float, targetHeight: Float, zoom: Float): Rect {
        val factor = max(targetWidth / sourceWidth, targetHeight / sourceHeight) * zoom
        val visibleWidth = (targetWidth / factor).coerceAtMost(sourceWidth.toFloat())
        val visibleHeight = (targetHeight / factor).coerceAtMost(sourceHeight.toFloat())
        val left = ((sourceWidth - visibleWidth) / 2f).roundToInt().coerceAtLeast(0)
        val top = ((sourceHeight - visibleHeight) / 2f).roundToInt().coerceAtLeast(0)
        return Rect(left, top, min(sourceWidth, left + visibleWidth.roundToInt()), min(sourceHeight, top + visibleHeight.roundToInt()))
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        val suffix = "…"
        val count = paint.breakText(text, true, (maxWidth - paint.measureText(suffix)).coerceAtLeast(0f), null)
        return text.take(count) + suffix
    }

    private fun watermarkTypeface(context: Context?, bold: Boolean): Typeface = context?.let {
        runCatching {
            Typeface.createFromAsset(it.assets, "watermark_fonts/${if (bold) "AlibabaPuHuiTi-2-85-Bold.otf" else "AlibabaPuHuiTi-2-45-Light.otf"}")
        }.getOrNull()
    } ?: Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)

    private fun brandLogoResource(context: Context, make: String?): Int {
        val key = make.orEmpty().lowercase(Locale.ROOT)
        val resourceName = when {
            "nikon" in key -> "wm_nikon"
            "canon" in key -> "wm_canon"
            "sony" in key -> "wm_sony"
            "fujifilm" in key || key == "fuji" -> "wm_fujifilm"
            "panasonic" in key || "lumix" in key -> "wm_panasonic"
            "leica" in key -> "wm_leica_logo"
            "hasselblad" in key -> "wm_hasselblad"
            "pentax" in key -> "wm_pentax"
            "ricoh" in key -> "wm_ricoh"
            "olympus" in key || "om digital" in key -> "wm_olympus_blue_gold"
            else -> return 0
        }
        return context.resources.getIdentifier(resourceName, "drawable", context.packageName)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun PhotoMetadata.cameraLabel(preset: WatermarkPreset): String? {
        val brand = cameraBrand.cleanDisplay().takeIf { WatermarkField.CAMERA_BRAND in preset.fields }
        val model = cameraModel.cleanDisplay().takeIf { WatermarkField.CAMERA_MODEL in preset.fields }
        if (brand != null && model != null && model.startsWith(brand, ignoreCase = true)) return model
        return listOfNotNull(brand, model).joinToString(" ").ifBlank { null }
    }

    private fun PhotoMetadata.lensLabel(preset: WatermarkPreset): String? = lensModel.cleanDisplay()
        .takeIf { WatermarkField.LENS_MODEL in preset.fields }

    private fun PhotoMetadata.shootingLabel(preset: WatermarkPreset, separator: String): String? = listOfNotNull(
        focalLength.cleanDisplay().takeIf { WatermarkField.FOCAL_LENGTH in preset.fields },
        equivalentFocalLength.cleanDisplay()?.let { "等效 $it" }.takeIf { WatermarkField.EQUIVALENT_FOCAL_LENGTH in preset.fields },
        aperture.cleanDisplay().takeIf { WatermarkField.APERTURE in preset.fields },
        shutterSpeed.cleanDisplay().takeIf { WatermarkField.SHUTTER in preset.fields },
        iso?.takeIf { it > 0 && WatermarkField.ISO in preset.fields }?.let { "ISO$it" },
    ).joinToString(separator).ifBlank { null }

    private fun PhotoMetadata.dateLabel(preset: WatermarkPreset): String? = capturedAt
        ?.takeIf { WatermarkField.CAPTURE_DATE in preset.fields }
        ?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(it) }

    private fun PhotoMetadata.captionLabel(preset: WatermarkPreset): String? = listOfNotNull(
        preset.customText.cleanDisplay().takeIf { WatermarkField.CUSTOM_TEXT in preset.fields },
        preset.copyrightText.cleanDisplay()?.let { "© $it" }.takeIf { WatermarkField.COPYRIGHT in preset.fields },
    ).joinToString(" · ").ifBlank { null }

    private fun PhotoMetadata.secondaryInfoLabel(preset: WatermarkPreset, firstLine: String?): String? {
        val candidates = listOf(cameraLabel(preset), captionLabel(preset), dateLabel(preset))
        return candidates.firstOrNull { it != null && it != firstLine }
    }

    private fun String?.cleanDisplay(): String? = this?.trim()?.takeIf {
        it.isNotBlank() && !it.equals("unknown", true) && it != "0mm" && it != "ISO0" && it != "null"
    }
}
