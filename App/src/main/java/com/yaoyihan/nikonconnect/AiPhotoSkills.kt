package com.yaoyihan.nikonconnect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.roundToInt

internal fun AiPhotoStyle.readPrompt(context: Context): String {
    val root = "ai_skills/$assetName"
    val skill = context.assets.open("$root/SKILL.md").bufferedReader().use { it.readText() }
    val references = if (this == AiPhotoStyle.MEMORY_ARCHIVE) {
        listOf("composition-system.md", "visual-language.md", "quality-check.md").joinToString("\n\n") { name ->
            context.assets.open("$root/references/$name").bufferedReader().use { it.readText() }
        }
    } else ""
    return if (references.isBlank()) skill else "$skill\n\n$references"
}

internal fun aiSkillGenerationPrompt(style: AiPhotoStyle, prompt: String, title: String, width: Int, height: Int,
    postcardBack: Boolean): String {
    // The provider renders pixels in one call, not an agent's tool/chat workflow.
    aiPhotoPrompt(prompt, title, width, height)
    val caption = if (title.isBlank()) "Author only the short scene-derived text required by the skill; never invent metadata."
        else "Use this exact user-supplied title: ${title.trim()}."
    val output = when (style) {
        AiPhotoStyle.MEMORY_ARCHIVE -> "Generate only ONE complete companion panel, same $width:$height aspect ratio as the reference. Do not render the source photo or the final diptych. The app will stitch the actual unchanged photograph with your completed panel."
        AiPhotoStyle.ZINE_POSTCARD -> "Generate only the ${if (postcardBack) "BACK" else "FRONT"} face as one independent 2:3 portrait image, not both sides or a contact sheet."
        AiPhotoStyle.GATHERED_ZINE -> "Generate one independent 3:5 portrait poster."
        else -> "Generate one independent 3:4 portrait image."
    }
    return "Read the following original visual skill as the design specification. Inspect reference image 1 for source-specific content. Apply its visual rules, not its chat response formatting or tool instructions. Return only the finished image, not Markdown, analysis, or a prompt.\n\n$prompt\n\nCurrent image API task (overrides multi-output/tool/model instructions only):\n$output\n$caption\nReference image 1 supplies all content. Any reference image 2 supplies style and layout only; never copy its subjects, text, or palette. Use the configured image model. Do not initiate other requests or retries."
}

internal fun aiArchiveSource(source: Bitmap, rotation: Int): Bitmap {
    val scale = minOf(1f, 2048f / maxOf(source.width, source.height))
    val resized = Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt().coerceAtLeast(1),
        (source.height * scale).roundToInt().coerceAtLeast(1), true)
    val oriented = OrientedBitmaps.rotate(resized, rotation)
    val copy = oriented.copy(Bitmap.Config.ARGB_8888, false)
    if (oriented !== resized) oriented.recycle()
    if (resized !== source) resized.recycle()
    return copy
}

internal fun aiStitchMemoryArchive(source: Bitmap, companion: Bitmap): Bitmap {
    val portrait = source.height > source.width
    val output = Bitmap.createBitmap(if (portrait) source.width * 2 else source.width,
        if (portrait) source.height else source.height * 2, Bitmap.Config.ARGB_8888)
    val left = if (portrait) source.width else 0
    val top = if (portrait) 0 else source.height
    val scale = minOf(source.width.toFloat() / companion.width, source.height.toFloat() / companion.height)
    val width = (companion.width * scale).roundToInt().coerceAtLeast(1)
    val height = (companion.height * scale).roundToInt().coerceAtLeast(1)
    val x = left + (source.width - width) / 2
    val y = top + (source.height - height) / 2
    Canvas(output).apply {
        drawColor(android.graphics.Color.rgb(250, 249, 246))
        drawBitmap(source, 0f, 0f, null)
        drawBitmap(companion, null, Rect(x, y, x + width, y + height), Paint(Paint.FILTER_BITMAP_FLAG))
    }
    return output
}
