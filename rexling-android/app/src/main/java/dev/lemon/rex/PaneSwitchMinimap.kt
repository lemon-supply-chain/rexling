package dev.lemon.rex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

/** Read-only map of the same server geometry shown in the pane drawer. */
@Composable
internal fun PaneSwitchMinimap(
    layout: List<PanePlacement>,
    active: String,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val localHaze = remember { HazeState() }
    val haze = hazeState ?: localHaze
    val context = androidx.compose.ui.platform.LocalContext.current
    PanePositionMap(
        layout,
        active,
        modifier.paneFrost(haze, RoundedCornerShape(5.dp), edge = false).semantics {
            contentDescription = context.getString(R.string.a11y_pane_switch_layout)
            stateDescription = active
        },
    )
}

/** Draws the window's geometry with one pane highlighted. */
@Composable
internal fun PanePositionMap(
    layout: List<PanePlacement>,
    active: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier) {
        val inset = 2.dp.toPx()
        val gap = 1.dp.toPx()
        val width = (size.width - 2 * inset).coerceAtLeast(0f)
        val height = (size.height - 2 * inset).coerceAtLeast(0f)
        layout.forEach { pane ->
            val w = (pane.width * width - gap).coerceAtLeast(0f)
            val h = (pane.height * height - gap).coerceAtLeast(0f)
            val radius = minOf(2.dp.toPx(), w / 2, h / 2)
            drawRoundRect(
                if (pane.id == active) scheme.primary
                else if (pane.floating) scheme.outlineVariant else scheme.onSurfaceVariant,
                Offset(inset + pane.x * width + gap / 2, inset + pane.y * height + gap / 2),
                Size(w, h),
                CornerRadius(radius),
            )
        }
        drawRoundRect(
            scheme.outlineVariant,
            cornerRadius = CornerRadius(5.dp.toPx()),
            style = Stroke(.7.dp.toPx()),
        )
    }
}
