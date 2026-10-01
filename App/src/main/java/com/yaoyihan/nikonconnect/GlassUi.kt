package com.yaoyihan.nikonconnect

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

internal fun bridgeBackgroundColors(theme: AppColorTheme): List<Color> = when (theme) {
    AppColorTheme.DARKROOM_ORANGE -> listOf(Color(0xFFD98B60), Color(0xFFA18B80), Color(0xFF706972))
    AppColorTheme.NIKON_YELLOW -> listOf(Color(0xFFBAA044), Color(0xFF999078), Color(0xFF696D68))
    AppColorTheme.PROFESSIONAL_GRAY -> listOf(Color(0xFF85919C), Color(0xFF7C858D), Color(0xFF555E68))
    AppColorTheme.DEEP_BLUE -> listOf(Color(0xFF548FBF), Color(0xFF7B92A5), Color(0xFF596876))
}

// A smooth backdrop needs only translucent diffusion to read as frosted glass.
// Keep text outside any blur layer and avoid offscreen captures during scrolling.
private val DefaultGlassShape = RoundedCornerShape(24.dp)
private val GlassFillColors = listOf(Color.White.copy(alpha = .28f), Color.White.copy(alpha = .16f), Color.White.copy(alpha = .22f))
private val GlassEdgeColors = listOf(Color.White.copy(alpha = .65f), Color.White.copy(alpha = .22f), Color.White.copy(alpha = .07f), Color.White.copy(alpha = .30f))

internal fun Modifier.bridgeGlass(shape: Shape = DefaultGlassShape): Modifier =
    clip(shape)
        .drawWithCache {
            // Scrolling changes placement, not size: reuse shaders and the border outline.
            val fill = Brush.linearGradient(GlassFillColors)
            val edge = Brush.linearGradient(GlassEdgeColors)
            val width = 1.dp.toPx().coerceAtMost(size.minDimension)
            val outline = shape.createOutline(Size((size.width - width).coerceAtLeast(0f), (size.height - width).coerceAtLeast(0f)), layoutDirection, this)
            val stroke = Stroke(width)
            onDrawWithContent {
                drawRect(fill)
                drawContent()
                if (width > 0f) translate(width / 2f, width / 2f) { drawOutline(outline, edge, style = stroke) }
            }
        }

@Composable
internal fun Modifier.bridgeBackground(): Modifier = background(BridgeBackground)
