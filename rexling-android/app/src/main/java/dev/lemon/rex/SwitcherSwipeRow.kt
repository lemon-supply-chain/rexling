package dev.lemon.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

internal data class SwitcherRowAction(
    val label: String,
    val icon: ChromeIcon,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val run: () -> Unit,
)

@Composable internal fun SwitcherSwipeRow(
    revealed: Boolean,
    onReveal: (Boolean) -> Unit,
    actions: List<SwitcherRowAction>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = remember { AnchoredDraggableState(false) }
    val notify by rememberUpdatedState(onReveal)
    val canSwipe = enabled && actions.any { it.enabled }
    val (regular, destructive) = actions.partition { !it.destructive }
    val islandGap = if (regular.isNotEmpty() && destructive.isNotEmpty()) 6.dp else 0.dp
    val colors = MaterialTheme.colorScheme
    val threshold = with(LocalDensity.current) { 36.dp.toPx() }
    val fling = AnchoredDraggableDefaults.flingBehavior(state, positionalThreshold = { minOf(threshold, it / 2) })

    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.drop(1).collect { notify(it) }
    }
    LaunchedEffect(revealed, canSwipe) {
        if ((!revealed || !canSwipe) && state.settledValue) state.animateTo(false)
    }

    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(colors.surfaceVariant.copy(alpha = .35f)).anchoredDraggable(
        state, Orientation.Horizontal, enabled = canSwipe, flingBehavior = fling,
    )) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Row(
                Modifier.width(46.dp * actions.size + islandGap + 12.dp).onSizeChanged { size ->
                    state.updateAnchors(DraggableAnchors {
                        false at 0f
                        if (actions.isNotEmpty() && size.width > 0) true at -size.width.toFloat()
                    })
                }.padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(islandGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val visible = state.settledValue && revealed
                if (regular.isNotEmpty()) {
                    SwipeActionIsland(
                        regular, colors.primaryContainer, colors.onPrimaryContainer,
                        visible, enabled, onReveal, Modifier.weight(regular.size.toFloat()),
                    )
                }
                if (destructive.isNotEmpty()) {
                    SwipeActionIsland(
                        destructive, colors.errorContainer, colors.onErrorContainer,
                        visible, enabled, onReveal, Modifier.weight(destructive.size.toFloat()),
                    )
                }
            }
        }
        Box(Modifier.offset { IntOffset(state.offset.takeUnless { it.isNaN() }?.roundToInt() ?: 0, 0) }
            .background(colors.surface)) {
            content()
        }
    }
}

@Composable private fun SwipeActionIsland(
    actions: List<SwitcherRowAction>,
    background: Color,
    tint: Color,
    visible: Boolean,
    enabled: Boolean,
    onReveal: (Boolean) -> Unit,
    modifier: Modifier,
) {
    Row(modifier.height(40.dp).clip(RoundedCornerShape(percent = 50)).background(background)) {
        for (action in actions) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .then(if (visible) Modifier.clickable(enabled && action.enabled, role = Role.Button) {
                        onReveal(false)
                        action.run()
                    }.semantics { contentDescription = action.label } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                ChromeSymbol(action.icon, Modifier.size(20.dp), if (action.enabled) tint else tint.copy(alpha = .38f))
            }
        }
    }
}
