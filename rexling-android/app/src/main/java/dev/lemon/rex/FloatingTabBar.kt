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
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.unit.Dp
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

private enum class FloatingEdge(val vertical: Boolean, val rotation: Float) {
    LEFT(vertical = false, rotation = -90f),
    RIGHT(vertical = false, rotation = 90f),
    TOP(vertical = true, rotation = 0f),
    BOTTOM(vertical = true, rotation = 180f);

    fun shape(radius: Dp) =
        when (this) {
            LEFT -> RoundedCornerShape(topEnd = radius, bottomEnd = radius)
            RIGHT -> RoundedCornerShape(topStart = radius, bottomStart = radius)
            TOP -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
            BOTTOM -> RoundedCornerShape(topStart = radius, topEnd = radius)
        }
}

private fun nearestEdge(
    pos: Float,
    barSize: Int,
    areaSize: Float,
    fraction: Float,
    margin: Float,
    edgeZone: Float,
    start: FloatingEdge,
    end: FloatingEdge,
): FloatingEdge? {
    val distStart = pos - margin
    val distEnd = (areaSize - margin) - (pos + barSize)
    return when {
        distStart <= edgeZone &&
            (distStart < distEnd || (distStart == distEnd && fraction < .5f)) -> start
        distEnd <= edgeZone -> end
        else -> null
    }
}

private fun centeredOffset(barPos: Float, barSize: Int, expandSize: Float, areaSize: Float) =
    (barPos + barSize / 2f - expandSize / 2f)
        .coerceIn(0f, (areaSize - expandSize).coerceAtLeast(0f))
        .roundToInt()

/**
 * A tab bar that floats over the workspace instead of taking layout space. The grip rides in the
 * bar itself: long-press it until it shows the move icon, then drag. Releasing parks the bar, and
 * the position persists, so moving it never resizes the terminal.
 *
 * When [hideAtEdges] is enabled, a bar parked along either edge perpendicular to [barOrientation]
 * tucks itself away after [idleHideMillis] without a touch, leaving a chevron that brings it back.
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
    barOrientation: Orientation = Orientation.Vertical,
    keepVisible: Boolean = false,
    topOcclusion: () -> Int = { 0 },
    onDragStart: () -> Unit = {},
    idleHideMillis: Long = FloatingTabBarIdleHideMillis,
    content: @Composable (handle: @Composable () -> Unit) -> Unit,
) {
    BoxWithConstraints(
        modifier.fillMaxSize().layout { measurable, constraints ->
            val inset = topOcclusion().coerceIn(0, constraints.maxHeight)
            val child =
                measurable.measure(
                    constraints.copy(
                        minHeight = (constraints.minHeight - inset).coerceAtLeast(0),
                        maxHeight = constraints.maxHeight - inset,
                    ),
                )
            layout(child.width, child.height + inset) { child.place(0, inset) }
        },
    ) {
        val density = LocalDensity.current
        val scheme = MaterialTheme.colorScheme
        val motion = LocalMotionEnabled.current
        val haptic = LocalHapticFeedback.current
        val margin = with(density) { FloatingTabBarMargin.toPx() }
        val edgeZone = with(density) { FloatingTabBarEdgeZone.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        var drag by remember { mutableStateOf<Offset?>(null) }
        var moving by remember { mutableStateOf(false) }
        var touching by remember { mutableStateOf(false) }
        var hidden by remember { mutableStateOf(false) }
        // Bumped by any touch on the bar, so the idle timer starts over.
        var interaction by remember { mutableIntStateOf(0) }
        val areaWidth = constraints.maxWidth.toFloat()
        val areaHeight = constraints.maxHeight.toFloat()
        val freeWidth = (areaWidth - size.width - margin * 2).coerceAtLeast(0f)
        val freeHeight = (areaHeight - size.height - margin * 2).coerceAtLeast(0f)
        val resting = Offset(margin + position.x * freeWidth, margin + position.y * freeHeight)
        val at = drag ?: resting
        fun clamped(at: Offset, marginPx: Float, freeW: Float, freeH: Float) =
            Offset(
                at.x.coerceIn(marginPx, marginPx + freeW),
                at.y.coerceIn(marginPx, marginPx + freeH),
            )
        fun parked(at: Offset, marginPx: Float, freeW: Float, freeH: Float) =
            Offset(
                if (freeW > 0f) ((at.x - marginPx) / freeW).coerceIn(0f, 1f) else 0f,
                if (freeH > 0f) ((at.y - marginPx) / freeH).coerceIn(0f, 1f) else 0f,
            )
        val currentResting by rememberUpdatedState(resting)
        val currentMargin by rememberUpdatedState(margin)
        val currentFreeWidth by rememberUpdatedState(freeWidth)
        val currentFreeHeight by rememberUpdatedState(freeHeight)
        val currentOnPosition by rememberUpdatedState(onPosition)
        val currentOnDragStart by rememberUpdatedState(onDragStart)
        fun reveal(scale: Float): EnterTransition =
            if (motion) fadeIn(tween(160)) + scaleIn(tween(160), initialScale = scale)
            else EnterTransition.None
        fun dismiss(scale: Float): ExitTransition =
            if (motion) fadeOut(tween(140)) + scaleOut(tween(140), targetScale = scale)
            else ExitTransition.None
        val edge =
            when {
                !hideAtEdges || size.width == 0 || size.height == 0 -> null
                barOrientation == Orientation.Horizontal ->
                    nearestEdge(
                        resting.y,
                        size.height,
                        areaHeight,
                        position.y,
                        margin,
                        edgeZone,
                        FloatingEdge.TOP,
                        FloatingEdge.BOTTOM,
                    )
                else ->
                    nearestEdge(
                        resting.x,
                        size.width,
                        areaWidth,
                        position.x,
                        margin,
                        edgeZone,
                        FloatingEdge.LEFT,
                        FloatingEdge.RIGHT,
                    )
            }
        var lastEdge by
            remember(barOrientation) {
                mutableStateOf(
                    if (barOrientation == Orientation.Horizontal) FloatingEdge.BOTTOM
                    else FloatingEdge.RIGHT,
                )
            }
        SideEffect { if (edge != null) lastEdge = edge }
        val renderEdge = edge ?: lastEdge
        LaunchedEffect(edge, interaction, moving, touching, keepVisible) {
            hidden = false
            if (edge == null || moving || touching || keepVisible) return@LaunchedEffect
            delay(idleHideMillis)
            hidden = true
        }
        // The bar keeps a margin from the workspace edges, so the grip never leaves the screen.
        val contentMaxWidth = (maxWidth - FloatingTabBarMargin * 2).coerceAtLeast(48.dp)
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
                    .pointerInput(Unit) {
                        try {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    moving = true
                                    drag = currentResting
                                    currentOnDragStart()
                                    haptic.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType
                                            .LongPress,
                                    )
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag = drag?.let {
                                        clamped(
                                            it + amount,
                                            currentMargin,
                                            currentFreeWidth,
                                            currentFreeHeight,
                                        )
                                    }
                                },
                                onDragEnd = {
                                    drag?.let {
                                        currentOnPosition(
                                            parked(
                                                it,
                                                currentMargin,
                                                currentFreeWidth,
                                                currentFreeHeight,
                                            ),
                                        )
                                    }
                                    drag = null
                                    moving = false
                                },
                                onDragCancel = {
                                    drag = null
                                    moving = false
                                },
                            )
                        } finally {
                            drag = null
                            moving = false
                        }
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
                            touching = true
                            interaction++
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.none { it.pressed }) break
                                }
                            } finally {
                                touching = false
                                interaction++
                            }
                        }
                    }
                    .testTag("Floating tab overlay"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.widthIn(max = contentMaxWidth).heightIn(max = contentMax)) {
                    content(handle)
                }
            }
        }
        // The tucked-away handle is half a capsule embedded in the screen edge: only the inner
        // half is visible, level with the bar it belongs to.
        val expandShort = 26.dp
        val expandLong = 52.dp
        val expandWidth = if (renderEdge.vertical) expandLong else expandShort
        val expandHeight = if (renderEdge.vertical) expandShort else expandLong
        val expandWidthPx = with(density) { expandWidth.toPx() }
        val expandHeightPx = with(density) { expandHeight.toPx() }
        val expandOffset =
            when (renderEdge) {
                FloatingEdge.LEFT,
                FloatingEdge.RIGHT ->
                    IntOffset(
                        if (renderEdge == FloatingEdge.LEFT) 0
                        else (areaWidth - expandWidthPx).coerceAtLeast(0f).roundToInt(),
                        centeredOffset(resting.y, size.height, expandHeightPx, areaHeight),
                    )
                FloatingEdge.TOP,
                FloatingEdge.BOTTOM ->
                    IntOffset(
                        centeredOffset(resting.x, size.width, expandWidthPx, areaWidth),
                        if (renderEdge == FloatingEdge.TOP) 0
                        else (areaHeight - expandHeightPx).coerceAtLeast(0f).roundToInt(),
                    )
            }
        AnimatedVisibility(
            visible = hidden && edge != null,
            enter = reveal(.8f),
            exit = dismiss(.8f),
            // The offset must sit on the visibility node itself, or the button's hit area stays at
            // the corner while its content is drawn at the edge.
            modifier = Modifier.offset { expandOffset },
        ) {
            val shape = renderEdge.shape(expandShort)
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
                    Modifier.size(16.dp).rotate(renderEdge.rotation),
                    scheme.onSurfaceVariant,
                )
            }
        }
    }
}
