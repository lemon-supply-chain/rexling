package dev.lemon.rex

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

private val flashColors =
    listOf(Color.Transparent, Color.White.copy(alpha = .85f), Color.Transparent)

/** Filled bar with an optional traveling highlight for working tabs. */
internal fun DrawScope.drawWorkingBar(
    color: Color,
    origin: Offset,
    size: Size,
    phase: Float? = null,
    vertical: Boolean = false,
    radius: CornerRadius = CornerRadius.Zero,
) {
    drawRoundRect(color, origin, size, radius)
    if (phase == null) return
    val length = if (vertical) size.height else size.width
    val start = (if (vertical) origin.y else origin.x) + (phase * 1.5f - .5f) * length
    val brush =
        if (vertical) Brush.verticalGradient(flashColors, start, start + length * .5f)
        else Brush.horizontalGradient(flashColors, start, start + length * .5f)
    drawRoundRect(brush, origin, size, radius)
}
