package dev.lemon.rex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

internal data class PaneIndicatorMark(val width: Float, val active: Float)

/** Expanding capsules share one constant amount of active width, including wraparound. */
internal fun paneIndicatorMarks(index: Int, count: Int, offset: Float): List<PaneIndicatorMark> {
    if (count <= 0) return emptyList()
    val visible = minOf(count, 7)
    fun slot(page: Int) = page - (page - 3).coerceIn(0, (count - visible).coerceAtLeast(0))
    val from = Math.floorMod(index, count)
    val step = if (offset > 0f) 1 else if (offset < 0f) -1 else 0
    val target = Math.floorMod(from + step, count)
    val start = slot(from)
    val end = slot(target)
    val progress = abs(offset).coerceIn(0f, 1f)
    return List(visible) { i ->
        val weight =
            if (start == end && i == start) 1f
            else if (i == start) 1f - progress else if (i == end) progress else 0f
        PaneIndicatorMark(5f + 10f * weight, weight)
    }
}

/** Progress is read in the draw phase; terminal views do not remeasure for the indicator. */
@Composable
internal fun PanePositionIndicator(
    index: Int,
    count: Int,
    offset: () -> Float,
    modifier: Modifier = Modifier,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    val visible = minOf(count.coerceAtLeast(0), 7)
    val trackWidth = if (visible == 0) 0f else visible * 5f + (visible - 1) * 4f + 10f
    Canvas(modifier.width(trackWidth.dp).height(14.dp)) {
        val marks = paneIndicatorMarks(index, count, offset())
        val scale = 1.dp.toPx()
        val h = 5.dp.toPx()
        val y = (size.height - h) / 2f
        var x = 0f
        val edges = ArrayList<Pair<Float, Float>>(marks.size)
        for (mark in marks) {
            val width = mark.width * scale
            edges.add(x to x + width)
            drawRoundRect(
                lerp(inactive, active, mark.active),
                Offset(x, y),
                Size(width, h),
                CornerRadius(h / 2f),
            )
            x += width + 4.dp.toPx()
        }
        // A light neck joins neighboring capsules midway through the transfer.
        // At the loop boundary the two end capsules exchange width without a
        // bridge across the intervening inactive dots.
        val moving = marks.indices.filter { marks[it].active > 0f }
        if (moving.size == 2 && moving[1] == moving[0] + 1) {
            val strength = sin(Math.PI * marks[moving[1]].active).toFloat().coerceAtLeast(0f)
            val left = edges[moving[0]].second - .5f * scale
            val right = edges[moving[1]].first + .5f * scale
            val middle = (left + right) / 2f
            val cy = size.height / 2f
            val neck = h * .32f * strength
            val path =
                Path().apply {
                    moveTo(left, cy - neck)
                    cubicTo(middle, cy - neck * .2f, middle, cy - neck * .2f, right, cy - neck)
                    lineTo(right, cy + neck)
                    cubicTo(middle, cy + neck * .2f, middle, cy + neck * .2f, left, cy + neck)
                    close()
                }
            drawPath(path, active.copy(alpha = .6f * strength)) // alpha-ok transient fade
        }
    }
}
