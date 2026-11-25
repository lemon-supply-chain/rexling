package dev.lemon.rex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The margin a floating tab bar keeps from the workspace edges. */
private val FloatingTabBarMargin = 8.dp

/** How close to a side edge a bar must rest before it tucks itself away. */
private val FloatingTabBarEdgeZone = 16.dp

/** How long a bar parked at a side edge waits after the last touch before hiding. */
private const val FloatingTabBarIdleHideMillis = 4_000L

/** The grip's touch target; it rides in the bar's own row or column. */
internal val FloatingTabBarHandle = 40.dp

private enum class FloatingEdge {
    LEFT,
    RIGHT,
}

/**
 * A tab bar that floats over the workspace instead of taking layout space. The grip rides in the
 * bar itself: long-press it until it shows the move icon, then drag. Releasing parks the bar, and
 * the position persists, so moving it never resizes the terminal.
 *
 * A left or right floating bar parked at a side edge tucks itself away after [idleHideMillis]
 * without a touch, leaving a chevron that brings it back. The bottom bar never hides.
 *
 * [position] is the bar's resting top-left as a fraction of the free space around it. The content
 * places the supplied grip in its row or column.
 */
@Composable
internal fun FloatingTabBar(
    position: Offset,
    onPosition: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    hideAtEdges: Boolean = false,
    keepVisible: Boolean = false,
    onDragStart: () -> Unit = {},
    idleHideMillis: Long = FloatingTabBarIdleHideMillis,
    content: @Composable (handle: @Composable () -> Unit) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scheme = MaterialTheme.colorScheme
        val motion = LocalMotionEnabled.current
        val haptic = LocalHapticFeedback.current
        val margin = with(density) { FloatingTabBarMargin.toPx() }
        val edgeZone = with(density) { FloatingTabBarEdgeZone.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        var drag by remember { mutableStateOf<Offset?>(null) }
        var moving by remember { mutableStateOf(false) }
        var hidden by remember { mutableStateOf(false) }
        // Bumped by any touch on the bar, so the idle timer starts over.
        var interaction by remember { mutableIntStateOf(0) }
        val areaWidth = constraints.maxWidth.toFloat()
        val areaHeight = constraints.maxHeight.toFloat()
        val freeWidth = (areaWidth - size.width - margin * 2).coerceAtLeast(0f)
        val freeHeight = (areaHeight - size.height - margin * 2).coerceAtLeast(0f)
        val resting = Offset(margin + position.x * freeWidth, margin + position.y * freeHeight)
        val at = drag ?: resting
        fun clamped(at: Offset) =
            Offset(
                at.x.coerceIn(margin, margin + freeWidth),
                at.y.coerceIn(margin, margin + freeHeight),
            )
        fun parked(at: Offset) =
            Offset(
                if (freeWidth > 0f) ((at.x - margin) / freeWidth).coerceIn(0f, 1f) else 0f,
                if (freeHeight > 0f) ((at.y - margin) / freeHeight).coerceIn(0f, 1f) else 0f,
            )
        fun reveal(scale: Float): EnterTransition =
            if (motion) fadeIn(tween(160)) + scaleIn(tween(160), initialScale = scale)
            else EnterTransition.None
        fun dismiss(scale: Float): ExitTransition =
            if (motion) fadeOut(tween(140)) + scaleOut(tween(140), targetScale = scale)
            else ExitTransition.None
        val edge =
            when {
                !hideAtEdges -> null
                resting.x <= margin + edgeZone -> FloatingEdge.LEFT
                resting.x + size.width >= areaWidth - margin - edgeZone -> FloatingEdge.RIGHT
                else -> null
            }
        LaunchedEffect(edge, interaction, moving, keepVisible) {
            hidden = false
            if (edge == null || moving || keepVisible) return@LaunchedEffect
            delay(idleHideMillis)
            hidden = true
        }
        // The bar keeps a margin from the workspace edges, so the grip never leaves the screen.
        val contentMax = (maxHeight - FloatingTabBarMargin * 2).coerceAtLeast(48.dp)
        val gripColor by
            animateColorAsState(
                if (moving) scheme.primaryContainer else Color.Transparent,
                if (motion) RexTokens.motion.fastEffects() else snap(),
                label = "Floating tab grip",
            )
        val moveDescription = stringResource(R.string.a11y_move_tab_bar)
        val moveUp = stringResource(R.string.move_up)
        val moveDown = stringResource(R.string.move_down)
        val moveLeft = stringResource(R.string.move_left)
        val moveRight = stringResource(R.string.move_right)
        val showDescription = stringResource(R.string.a11y_show_tab_bar)
        fun nudge(dx: Float, dy: Float) {
            onPosition(
                Offset(
                    (position.x + dx).coerceIn(0f, 1f),
                    (position.y + dy).coerceIn(0f, 1f),
                ),
            )
        }
        val handle: @Composable () -> Unit = {
            Box(
                Modifier.size(FloatingTabBarHandle)
                    .testTag("Floating tab handle")
                    .pointerInput(position, margin, freeWidth, freeHeight) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                moving = true
                                drag = resting
                                onDragStart()
                                haptic.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                )
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                drag = drag?.let { clamped(it + amount) }
                            },
                            onDragEnd = {
                                drag?.let { onPosition(parked(it)) }
                                drag = null
                                moving = false
                            },
                            onDragCancel = {
                                drag = null
                                moving = false
                            },
                        )
                    }
                    .semantics {
                        contentDescription = moveDescription
                        role = Role.Button
                        customActions =
                            listOf(
                                CustomAccessibilityAction(moveUp) {
                                    nudge(0f, -.1f)
                                    true
                                },
                                CustomAccessibilityAction(moveDown) {
                                    nudge(0f, .1f)
                                    true
                                },
                                CustomAccessibilityAction(moveLeft) {
                                    nudge(-.1f, 0f)
                                    true
                                },
                                CustomAccessibilityAction(moveRight) {
                                    nudge(.1f, 0f)
                                    true
                                },
                            )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(gripColor),
                )
                ChromeSymbol(
                    if (moving) ChromeIcon.MOVE else ChromeIcon.WAFFLE,
                    Modifier.size(16.dp),
                    if (moving) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(
            visible = !hidden,
            enter = reveal(.92f),
            exit = dismiss(.92f),
            modifier = Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) },
        ) {
            Column(
                Modifier.onSizeChanged { size = it }
                    // Until the bar is measured it cannot be placed; keep the first frame blank
                    // instead of flashing it in the corner.
                    .alpha(if (size.width == 0) 0f else 1f)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            interaction++
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    }
                    .testTag("Floating tab overlay"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.heightIn(max = contentMax)) { content(handle) }
            }
        }
        // The tucked-away handle is half a capsule embedded in the screen edge: only the inner
        // half is visible, level with the bar it belongs to.
        val expandWidth = 26.dp
        val expandHeight = 52.dp
        val expandWidthPx = with(density) { expandWidth.toPx() }
        val expandHeightPx = with(density) { expandHeight.toPx() }
        val expandX = if (edge == FloatingEdge.LEFT) 0f else areaWidth - expandWidthPx
        val expandY =
            (resting.y + size.height / 2f - expandHeightPx / 2f).coerceIn(
                0f,
                (areaHeight - expandHeightPx).coerceAtLeast(0f),
            )
        AnimatedVisibility(
            visible = hidden && edge != null,
            enter = reveal(.8f),
            exit = dismiss(.8f),
            // The offset must sit on the visibility node itself, or the button's hit area stays at
            // the corner while its content is drawn at the edge.
            modifier = Modifier.offset { IntOffset(expandX.roundToInt(), expandY.roundToInt()) },
        ) {
            val shape =
                if (edge == FloatingEdge.LEFT)
                    RoundedCornerShape(topEnd = expandWidth, bottomEnd = expandWidth)
                else RoundedCornerShape(topStart = expandWidth, bottomStart = expandWidth)
            Box(
                Modifier.size(expandWidth, expandHeight)
                    .shadow(2.dp, shape, clip = false)
                    .clip(shape)
                    .background(scheme.surface)
                    .clickable(role = Role.Button) {
                        interaction++
                        hidden = false
                    }
                    .semantics { contentDescription = showDescription }
                    .testTag("Floating tab expand"),
                contentAlignment = Alignment.Center,
            ) {
                ChromeSymbol(
                    ChromeIcon.CHEVRON,
                    Modifier.size(16.dp).rotate(if (edge == FloatingEdge.LEFT) -90f else 90f),
                    scheme.onSurfaceVariant,
                )
            }
        }
    }
}
