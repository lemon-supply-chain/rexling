package dev.lemon.rex

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos

internal fun workingBarBrightness(phase: Float): Float =
    (.725 - .275 * cos(phase * 2 * PI)).toFloat()

/** Vertical markers fill upward; horizontal markers fill across the tab. */
internal fun DrawScope.drawTabMarker(
    color: Color,
    origin: Offset,
    size: Size,
    percent: Int?,
    brightness: Float,
    vertical: Boolean = false,
) {
    if (percent != null)
        drawRoundRect(
            color.copy(alpha = .18f),
            origin,
            size,
            CornerRadius(size.minDimension / 2f),
        )
    val fraction = (percent ?: 100).coerceIn(0, 100) / 100f
    if (fraction == 0f) return
    val fill =
        if (vertical) size.copy(height = size.height * fraction)
        else size.copy(width = size.width * fraction)
    val start = if (vertical) origin.copy(y = origin.y + size.height - fill.height) else origin
    drawRoundRect(color.copy(alpha = brightness), start, fill, CornerRadius(fill.minDimension / 2f))
}
