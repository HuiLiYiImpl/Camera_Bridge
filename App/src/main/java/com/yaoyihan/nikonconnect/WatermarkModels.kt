package com.yaoyihan.nikonconnect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import kotlin.math.roundToInt

enum class WatermarkLayout(val title: String) {
    MINIMAL("极简底部信息条"),
    LEFT_PARAMS("左下角摄影参数"),
    RIGHT_PARAMS("右下角摄影参数"),
    WHITE_BORDER("白色底边框信息条"),
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
    TOP_LEFT("左上"),
    TOP_RIGHT("右上"),
}

data class WatermarkPreset(
    val id: String,
    val name: String,
    val layout: WatermarkLayout = WatermarkLayout.MINIMAL,
    val fields: Set<WatermarkField> = setOf(WatermarkField.CAMERA_BRAND, WatermarkField.CAMERA_MODEL, WatermarkField.CAPTURE_DATE),
    val fontSize: Int = 34,
    val textColor: Int = Color.WHITE,
    val backgroundColor: Int = Color.BLACK,
    val backgroundAlpha: Int = 68,
    val margin: Int = 28,
    val showBorder: Boolean = false,
    val customText: String = "",
    val copyrightText: String = "",
    val logoEnabled: Boolean = true,
    val useBrandLogo: Boolean = true,
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
        // These tags are not exposed as constants by the platform ExifInterface
        // on every API level supported by the app, so keep their standard names.
        val lens = getAttribute("LensModel").clean()
        val iso = getAttributeInt("PhotographicSensitivity", 0).takeIf { it > 0 }
            ?: getAttributeInt("ISOSpeedRatings", 0).takeIf { it > 0 }
        val exposure = getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { value ->
            if (value < 1.0) "1/${(1.0 / value).roundToInt()}s" else "${"%.2f".format(Locale.US, value)}s"
        }
        val fNumber = getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "f/${"%.1f".format(Locale.US, it)}" }
        val focal = getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "${it.roundToInt()}mm" }
        val equivalent = getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 }?.let { "${it}mm" }
        val date = getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.parseExifDate()
            ?: getAttribute(ExifInterface.TAG_DATETIME)?.parseExifDate()
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
            customText = fallback.customText,
            copyrightText = getAttribute(ExifInterface.TAG_COPYRIGHT).clean() ?: fallback.copyrightText,
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotBlank() && !it.equals("unknown", true) && it != "0mm" }
    private fun String.parseExifDate(): Date? = runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(this) }.getOrNull()
}

object WatermarkRenderer {
    fun render(source: Bitmap, metadata: PhotoMetadata, preset: WatermarkPreset, context: Context? = null): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val scale = output.width / 1080f
        val textSize = max(18f, preset.fontSize * scale)
        val lines = metadata.lines(preset)
        val hasBrandLogo = context != null && preset.logoEnabled && preset.useBrandLogo && brandLogoResource(context, metadata.cameraBrand) != 0
        if (lines.isEmpty() && preset.logoUri == null && !hasBrandLogo) return output
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = preset.textColor; this.textSize = textSize; typeface = watermarkTypeface(context, false) }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = preset.textColor; this.textSize = textSize; typeface = watermarkTypeface(context, true) }
        val lineHeight = textSize * 1.28f
        val padding = preset.margin * scale
        val blockHeight = max(lineHeight + padding * 2, lines.size * lineHeight + padding * 2)
        val blockWidth = output.width * if (preset.layout == WatermarkLayout.WHITE_BORDER || preset.layout == WatermarkLayout.MINIMAL) 1f else .62f
        val top = output.height - blockHeight - padding
        val left = when (preset.layout) {
            WatermarkLayout.RIGHT_PARAMS -> output.width - blockWidth - padding
            else -> padding
        }
        val right = when (preset.layout) {
            WatermarkLayout.RIGHT_PARAMS -> output.width - padding
            else -> minOf(output.width - padding, left + blockWidth)
        }
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(preset.backgroundAlpha.coerceIn(0, 255), Color.red(preset.backgroundColor), Color.green(preset.backgroundColor), Color.blue(preset.backgroundColor))
            style = Paint.Style.FILL
        }
        val rect = RectF(left, top, right, output.height.toFloat() - padding / 2)
        if (preset.layout != WatermarkLayout.CUSTOM) canvas.drawRoundRect(rect, 12f * scale, 12f * scale, background)
        if (preset.showBorder || preset.layout == WatermarkLayout.WHITE_BORDER) {
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (preset.layout == WatermarkLayout.WHITE_BORDER) Color.WHITE else preset.textColor; style = Paint.Style.STROKE; strokeWidth = max(1f, 2f * scale) }
            canvas.drawRoundRect(rect, 12f * scale, 12f * scale, border)
        }
        var baseline = top + padding + textSize
        lines.forEachIndexed { index, line ->
            val textPaint = if (index == 0) titlePaint else bodyPaint
            textPaint.textAlign = if (preset.layout == WatermarkLayout.RIGHT_PARAMS) Paint.Align.RIGHT else Paint.Align.LEFT
            val x = if (preset.layout == WatermarkLayout.RIGHT_PARAMS) right - padding else left + padding
            canvas.drawText(line, x, baseline, textPaint)
            baseline += lineHeight
        }
        if (context != null && preset.logoEnabled) drawLogo(canvas, context, preset, metadata.cameraBrand, left, top, right, scale)
        return output
    }

    private fun drawLogo(canvas: Canvas, context: Context, preset: WatermarkPreset, cameraBrand: String?, left: Float, top: Float, right: Float, scale: Float) {
        val logo = runCatching {
            if (preset.logoUri != null) context.contentResolver.openInputStream(Uri.parse(preset.logoUri))?.use(BitmapFactory::decodeStream)
            else if (preset.useBrandLogo) brandLogoResource(context, cameraBrand).takeIf { it != 0 }?.let { BitmapFactory.decodeResource(context.resources, it) }
            else null
        }.getOrNull() ?: return
        val target = (64f * scale * preset.logoScale / 100f).roundToInt().coerceAtLeast(16)
        val ratio = logo.width.toFloat() / logo.height.coerceAtLeast(1)
        val width = (target * ratio).roundToInt().coerceAtLeast(1)
        val rect = when (preset.logoPosition) {
            WatermarkLogoPosition.TOP_LEFT -> RectF(left + 18f * scale, top + 14f * scale, left + width + 18f * scale, top + target + 14f * scale)
            WatermarkLogoPosition.TOP_RIGHT -> RectF(right - width - 18f * scale, top + 14f * scale, right - 18f * scale, top + target + 14f * scale)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = (255 * preset.logoAlpha / 100f).roundToInt() }
        canvas.drawBitmap(logo, null, rect, paint)
        logo.recycle()
    }

    private fun watermarkTypeface(context: Context?, bold: Boolean): Typeface = context?.let {
        runCatching { Typeface.createFromAsset(it.assets, "watermark_fonts/${if (bold) "AlibabaPuHuiTi-2-85-Bold.otf" else "AlibabaPuHuiTi-2-45-Light.otf"}") }.getOrNull()
    } ?: Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)

    private fun brandLogoResource(context: Context, make: String?): Int {
        val key = when (make.orEmpty().lowercase(Locale.ROOT)) {
            in listOf("nikon", "nikon corporation") -> "wm_nikon"
            in listOf("canon", "canon inc.") -> "wm_canon"
            in listOf("sony", "sony corporation") -> "wm_sony"
            in listOf("fujifilm", "fuji") -> "wm_fujifilm"
            in listOf("panasonic", "panasonic corporation") -> "wm_panasonic"
            "leica" -> "wm_leica_logo"
            "hasselblad" -> "wm_hasselblad"
            "pentax" -> "wm_pentax"
            "ricoh" -> "wm_ricoh"
            "olympus" -> "wm_olympus_white_gold"
            else -> return 0
        }
        return context.resources.getIdentifier(key, "drawable", context.packageName)
    }

    private fun PhotoMetadata.lines(preset: WatermarkPreset): List<String> {
        val lines = mutableListOf<String>()
        if (WatermarkField.CAMERA_BRAND in preset.fields || WatermarkField.CAMERA_MODEL in preset.fields) {
            listOfNotNull(if (WatermarkField.CAMERA_BRAND in preset.fields) cameraBrand else null, if (WatermarkField.CAMERA_MODEL in preset.fields) cameraModel else null)
                .joinToString(" ").takeIf { it.isNotBlank() }?.let(lines::add)
        }
        if (WatermarkField.LENS_MODEL in preset.fields) lensModel?.let(lines::add)
        val shooting = listOfNotNull(
            if (WatermarkField.FOCAL_LENGTH in preset.fields) focalLength else null,
            if (WatermarkField.EQUIVALENT_FOCAL_LENGTH in preset.fields) equivalentFocalLength?.let { "等效 $it" } else null,
            if (WatermarkField.APERTURE in preset.fields) aperture else null,
            if (WatermarkField.SHUTTER in preset.fields) shutterSpeed else null,
            if (WatermarkField.ISO in preset.fields) iso?.let { "ISO $it" } else null,
        )
        if (shooting.isNotEmpty()) lines += shooting.joinToString(" · ")
        if (WatermarkField.CAPTURE_DATE in preset.fields) capturedAt?.let { SimpleDateFormat("yyyy.MM.dd · HH:mm", Locale.getDefault()).format(it) }?.let(lines::add)
        if (WatermarkField.CUSTOM_TEXT in preset.fields) preset.customText.trim().takeIf { it.isNotBlank() }?.let(lines::add)
        if (WatermarkField.COPYRIGHT in preset.fields) preset.copyrightText.trim().takeIf { it.isNotBlank() }?.let { lines += "© $it" }
        return lines
    }
}
