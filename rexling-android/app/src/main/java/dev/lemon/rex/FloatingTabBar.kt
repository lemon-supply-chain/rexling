package dev.lemon.rex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The margin a floating tab bar keeps from the workspace edges. */
private val FloatingTabBarMargin = 8.dp

/** Magnetic capture zone from a workspace margin when releasing a drag. */
private val FloatingTabBarSnapZone = 32.dp

/** Fallback proximity threshold when checking a static restored position against margins. */
private val FloatingTabBarEdgeZone = 16.dp

/** Flick velocity (dp/s) along the edge axis that snaps the bar to the approached edge. */
private val FloatingTabBarSnapVelocity = 600.dp

/** Decisive flick velocity (dp/s) that snaps to the edge and tucks immediately. */
private val FloatingTabBarTuckVelocity = 900.dp

/** Drag distance needed to tuck a visible edge bar or pull a tucked handle open. */
private val FloatingTabBarSwipeThreshold = 24.dp

/** How long a bar parked at a side edge waits without interaction before tucking away. */
private const val FloatingTabBarIdleHideMillis = 3_000L

/** How long a tucked edge handle waits before dimming to reduce distraction. */
private const val FloatingTabBarHandleDimDelayMillis = 1_600L

/** Resting opacity of an idle tucked handle over the terminal. */
private const val FloatingTabBarDimmedHandleAlpha = 0.52f

/** The grip's touch target; it rides in the bar's own row or column. */
internal val FloatingTabBarHandle = 40.dp

internal enum class FloatingEdge(val vertical: Boolean, val rotation: Float) {
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

internal data class FloatingParkResult(
    val position: Offset,
    val snappedEdge: FloatingEdge?,
    val tuckImmediately: Boolean,
)

private fun clampedPosition(at: Offset, marginPx: Float, freeW: Float, freeH: Float) =
    Offset(
        at.x.coerceIn(marginPx, marginPx + freeW),
        at.y.coerceIn(marginPx, marginPx + freeH),
    )

private fun parkedFraction(at: Offset, marginPx: Float, freeW: Float, freeH: Float) =
    Offset(
        if (freeW > 0f) ((at.x - marginPx) / freeW).coerceIn(0f, 1f) else 0f,
        if (freeH > 0f) ((at.y - marginPx) / freeH).coerceIn(0f, 1f) else 0f,
    )

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

internal fun resolveFloatingPark(
    releasedAt: Offset,
    velocityPxPerSec: Offset,
    barSize: IntSize,
    areaWidth: Float,
    areaHeight: Float,
    marginPx: Float,
    snapZonePx: Float,
    snapVelocityPxPerSec: Float,
    tuckVelocityPxPerSec: Float,
    hideAtEdges: Boolean,
    barOrientation: Orientation,
): FloatingParkResult {
    val freeW = (areaWidth - barSize.width - marginPx * 2f).coerceAtLeast(0f)
    val freeH = (areaHeight - barSize.height - marginPx * 2f).coerceAtLeast(0f)
    val clamped = clampedPosition(releasedAt, marginPx, freeW, freeH)
    val raw = parkedFraction(clamped, marginPx, freeW, freeH)
    if (!hideAtEdges || barSize.width <= 0 || barSize.height <= 0) {
        return FloatingParkResult(raw, snappedEdge = null, tuckImmediately = false)
    }
    val verticalAxis = barOrientation == Orientation.Horizontal
    val pos = if (verticalAxis) clamped.y else clamped.x
    val size = if (verticalAxis) barSize.height else barSize.width
    val area = if (verticalAxis) areaHeight else areaWidth
    val fraction = if (verticalAxis) raw.y else raw.x
    val vel = if (verticalAxis) velocityPxPerSec.y else velocityPxPerSec.x
    val crossVel = if (verticalAxis) abs(velocityPxPerSec.x) else abs(velocityPxPerSec.y)
    val startEdge = if (verticalAxis) FloatingEdge.TOP else FloatingEdge.LEFT
    val endEdge = if (verticalAxis) FloatingEdge.BOTTOM else FloatingEdge.RIGHT
    val distStart = (pos - marginPx).coerceAtLeast(0f)
    val distEnd = ((area - marginPx) - (pos + size)).coerceAtLeast(0f)

    var snappedFraction = fraction
    var snappedEdge: FloatingEdge? = null
    var tuckImmediately = false

    val flickDominant = abs(vel) >= snapVelocityPxPerSec && abs(vel) > crossVel
    if (flickDominant) {
        if (vel < 0f && distStart <= area * 0.45f) {
            snappedFraction = 0f
            snappedEdge = startEdge
            tuckImmediately = vel <= -tuckVelocityPxPerSec && distStart <= snapZonePx * 1.5f
        } else if (vel > 0f && distEnd <= area * 0.45f) {
            snappedFraction = 1f
            snappedEdge = endEdge
            tuckImmediately = vel >= tuckVelocityPxPerSec && distEnd <= snapZonePx * 1.5f
        }
    }
    if (snappedEdge == null) {
        when {
            distStart <= snapZonePx &&
                (distStart < distEnd || (distStart == distEnd && fraction < .5f)) -> {
                snappedFraction = 0f
                snappedEdge = startEdge
            }
            distEnd <= snapZonePx -> {
                snappedFraction = 1f
                snappedEdge = endEdge
            }
        }
    }
    val finalPos =
        if (verticalAxis) Offset(raw.x, snappedFraction) else Offset(snappedFraction, raw.y)
    return FloatingParkResult(finalPos, snappedEdge, tuckImmediately)
}

internal fun resolveRestingEdge(
    position: Offset,
    resting: Offset,
    barSize: IntSize,
    areaWidth: Float,
    areaHeight: Float,
    marginPx: Float,
    edgeZonePx: Float,
    hideAtEdges: Boolean,
    barOrientation: Orientation,
): FloatingEdge? {
    if (!hideAtEdges || barSize.width <= 0 || barSize.height <= 0) return null
    val verticalAxis = barOrientation == Orientation.Horizontal
    val fraction = if (verticalAxis) position.y else position.x
    val startEdge = if (verticalAxis) FloatingEdge.TOP else FloatingEdge.LEFT
    val endEdge = if (verticalAxis) FloatingEdge.BOTTOM else FloatingEdge.RIGHT
    if (fraction <= 0.001f) return startEdge
    if (fraction >= 0.999f) return endEdge
    return if (verticalAxis) {
        nearestEdge(
            resting.y,
            barSize.height,
            areaHeight,
            position.y,
            marginPx,
            edgeZonePx,
            FloatingEdge.TOP,
            FloatingEdge.BOTTOM,
        )
    } else {
        nearestEdge(
            resting.x,
            barSize.width,
            areaWidth,
            position.x,
            marginPx,
            edgeZonePx,
            FloatingEdge.LEFT,
            FloatingEdge.RIGHT,
        )
    }
}

private fun edgeDirectionalMatch(
    edge: FloatingEdge,
    delta: Offset,
    thresholdPx: Float,
    towardEdge: Boolean,
): Boolean {
    val signed = if (towardEdge) 1f else -1f
    val primary =
        when (edge) {
            FloatingEdge.LEFT -> -delta.x * signed
            FloatingEdge.RIGHT -> delta.x * signed
            FloatingEdge.TOP -> -delta.y * signed
            FloatingEdge.BOTTOM -> delta.y * signed
        }
    val cross = if (edge.vertical) abs(delta.x) else abs(delta.y)
    return primary >= thresholdPx && primary > cross * 1.25f
}

internal fun isSwipeTowardEdge(edge: FloatingEdge, delta: Offset, thresholdPx: Float): Boolean =
    edgeDirectionalMatch(edge, delta, thresholdPx, towardEdge = true)

internal fun isSwipeAwayFromEdge(edge: FloatingEdge, delta: Offset, thresholdPx: Float): Boolean =
    edgeDirectionalMatch(edge, delta, thresholdPx, towardEdge = false)

private fun centeredOffset(barPos: Float, barSize: Int, expandSize: Float, areaSize: Float) =
    (barPos + barSize / 2f - expandSize / 2f)
        .coerceIn(0f, (areaSize - expandSize).coerceAtLeast(0f))
        .roundToInt()

private fun edgeEnter(edge: FloatingEdge, motion: Boolean): EnterTransition {
    if (!motion) return EnterTransition.None
    val slideSpec = tween<IntOffset>(180)
    val fade = fadeIn(tween(140))
    return when (edge) {
        FloatingEdge.LEFT -> slideInHorizontally(slideSpec) { -it } + fade
        FloatingEdge.RIGHT -> slideInHorizontally(slideSpec) { it } + fade
        FloatingEdge.TOP -> slideInVertically(slideSpec) { -it } + fade
        FloatingEdge.BOTTOM -> slideInVertically(slideSpec) { it } + fade
    }
}

private fun edgeExit(edge: FloatingEdge, motion: Boolean): ExitTransition {
    if (!motion) return ExitTransition.None
    val slideSpec = tween<IntOffset>(160)
    val fade = fadeOut(tween(120))
    return when (edge) {
        FloatingEdge.LEFT -> slideOutHorizontally(slideSpec) { -it } + fade
        FloatingEdge.RIGHT -> slideOutHorizontally(slideSpec) { it } + fade
        FloatingEdge.TOP -> slideOutVertically(slideSpec) { -it } + fade
        FloatingEdge.BOTTOM -> slideOutVertically(slideSpec) { it } + fade
    }
}

/**
 * A tab bar that floats over the workspace instead of taking layout space. The grip rides in the
 * bar itself: long-press it until it shows the move icon, then drag. Releasing near an edge or
 * flicking toward it magnetically snaps and settles smoothly with a spring.
 *
 * When [hideAtEdges] is enabled, a bar parked along either edge perpendicular to [barOrientation]
 * tucks itself away after [idleHideMillis] without interaction, on explicit [dismissSignal] events,
 * or when swiped toward that edge, leaving a quiet edge handle that reveals on tap or inward pull.
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
    dismissSignal: Int = 0,
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
        val snapZone = with(density) { FloatingTabBarSnapZone.toPx() }
        val edgeZone = with(density) { FloatingTabBarEdgeZone.toPx() }
        val snapVelocity = with(density) { FloatingTabBarSnapVelocity.toPx() }
        val tuckVelocity = with(density) { FloatingTabBarTuckVelocity.toPx() }
        val swipeThreshold = with(density) { FloatingTabBarSwipeThreshold.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        var drag by remember { mutableStateOf<Offset?>(null) }
        var moving by remember { mutableStateOf(false) }
        var touching by remember { mutableStateOf(false) }
        var hidden by remember { mutableStateOf(false) }
        var placed by remember { mutableStateOf(false) }
        var animateNextSettle by remember { mutableStateOf(false) }
        var settleFrom by remember { mutableStateOf<Offset?>(null) }
        val animatedResting = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        // Bumped by any touch or reveal on the bar, so the idle timer starts over.
        var interaction by remember { mutableIntStateOf(0) }
        val areaWidth = constraints.maxWidth.toFloat()
        val areaHeight = constraints.maxHeight.toFloat()
        val freeWidth = (areaWidth - size.width - margin * 2).coerceAtLeast(0f)
        val freeHeight = (areaHeight - size.height - margin * 2).coerceAtLeast(0f)
        val resting = Offset(margin + position.x * freeWidth, margin + position.y * freeHeight)
        LaunchedEffect(resting, settleFrom, size, motion) {
            if (size.width == 0 || size.height == 0 || drag != null) return@LaunchedEffect
            val from = settleFrom
            settleFrom = null
            if (from != null) animatedResting.snapTo(from)
            if (placed && (from != null || animateNextSettle) && motion) {
                animateNextSettle = false
                animatedResting.animateTo(resting, RexTokens.motion.fastSpatial())
            } else {
                animateNextSettle = false
                animatedResting.snapTo(resting)
                placed = true
            }
        }
        val at = drag ?: if (placed) animatedResting.value else resting
        val currentPosition by rememberUpdatedState(position)
        val currentResting by rememberUpdatedState(resting)
        val currentSize by rememberUpdatedState(size)
        val currentAreaWidth by rememberUpdatedState(areaWidth)
        val currentAreaHeight by rememberUpdatedState(areaHeight)
        val currentMargin by rememberUpdatedState(margin)
        val currentFreeWidth by rememberUpdatedState(freeWidth)
        val currentFreeHeight by rememberUpdatedState(freeHeight)
        val currentHideAtEdges by rememberUpdatedState(hideAtEdges)
        val currentBarOrientation by rememberUpdatedState(barOrientation)
        val currentKeepVisible by rememberUpdatedState(keepVisible)
        val currentOnPosition by rememberUpdatedState(onPosition)
        val currentOnDragStart by rememberUpdatedState(onDragStart)
        val edge =
            resolveRestingEdge(
                position = position,
                resting = resting,
                barSize = size,
                areaWidth = areaWidth,
                areaHeight = areaHeight,
                marginPx = margin,
                edgeZonePx = edgeZone,
                hideAtEdges = hideAtEdges,
                barOrientation = barOrientation,
            )
        var lastEdge by
            remember(barOrientation) {
                mutableStateOf(
                    if (barOrientation == Orientation.Horizontal) FloatingEdge.BOTTOM
                    else FloatingEdge.RIGHT,
                )
            }
        SideEffect { if (edge != null) lastEdge = edge }
        val renderEdge = edge ?: lastEdge
        val currentEdge by rememberUpdatedState(edge)
        val currentMoving by rememberUpdatedState(moving)
        val currentHidden by rememberUpdatedState(hidden)
        LaunchedEffect(edge, keepVisible) { if (edge == null || keepVisible) hidden = false }
        var lastDismissSignal by remember { mutableIntStateOf(dismissSignal) }
        LaunchedEffect(dismissSignal) {
            if (dismissSignal == lastDismissSignal) return@LaunchedEffect
            lastDismissSignal = dismissSignal
            if (edge != null && !hidden && !moving && !touching && !keepVisible) {
                hidden = true
            }
        }
        LaunchedEffect(hidden, edge, interaction, moving, touching, keepVisible, idleHideMillis) {
            if (hidden || edge == null || moving || touching || keepVisible) {
                return@LaunchedEffect
            }
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
            animateNextSettle = true
            onPosition(
                Offset(
                    (position.x + dx).coerceIn(0f, 1f),
                    (position.y + dy).coerceIn(0f, 1f),
                ),
            )
        }
        val tracker = remember { VelocityTracker() }
        val handle: @Composable () -> Unit = {
            Box(
                Modifier.size(FloatingTabBarHandle)
                    .testTag("Floating tab handle")
                    .pointerInput(Unit) {
                        var trackedPos = Offset.Zero
                        try {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    moving = true
                                    tracker.resetTracking()
                                    trackedPos = currentResting
                                    drag = currentResting
                                    currentOnDragStart()
                                    haptic.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType
                                            .LongPress,
                                    )
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    trackedPos += amount
                                    tracker.addPosition(change.uptimeMillis, trackedPos)
                                    drag = drag?.let {
                                        clampedPosition(
                                            it + amount,
                                            currentMargin,
                                            currentFreeWidth,
                                            currentFreeHeight,
                                        )
                                    }
                                },
                                onDragEnd = {
                                    val released = drag
                                    val velocity = tracker.calculateVelocity()
                                    tracker.resetTracking()
                                    if (released != null) {
                                        val park =
                                            resolveFloatingPark(
                                                releasedAt = released,
                                                velocityPxPerSec = Offset(velocity.x, velocity.y),
                                                barSize = currentSize,
                                                areaWidth = currentAreaWidth,
                                                areaHeight = currentAreaHeight,
                                                marginPx = currentMargin,
                                                snapZonePx = snapZone,
                                                snapVelocityPxPerSec = snapVelocity,
                                                tuckVelocityPxPerSec = tuckVelocity,
                                                hideAtEdges = currentHideAtEdges,
                                                barOrientation = currentBarOrientation,
                                            )
                                        if (park.position != currentPosition) {
                                            animateNextSettle = true
                                            settleFrom = released
                                        }
                                        currentOnPosition(park.position)
                                        if (park.tuckImmediately && !currentKeepVisible) {
                                            hidden = true
                                        }
                                    }
                                    drag = null
                                    moving = false
                                },
                                onDragCancel = {
                                    tracker.resetTracking()
                                    drag?.let { cancelled ->
                                        animateNextSettle = true
                                        settleFrom = cancelled
                                    }
                                    drag = null
                                    moving = false
                                },
                            )
                        } finally {
                            tracker.resetTracking()
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
            enter = edgeEnter(renderEdge, motion),
            exit = edgeExit(renderEdge, motion),
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
                            var totalDelta = Offset.Zero
                            var tuckedBySwipe = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val activePointer = event.changes.firstOrNull { it.pressed }
                                    if (activePointer != null) {
                                        totalDelta +=
                                            activePointer.position - activePointer.previousPosition
                                        val activeEdge = currentEdge
                                        if (
                                            !tuckedBySwipe &&
                                                activeEdge != null &&
                                                !currentMoving &&
                                                !currentKeepVisible &&
                                                !currentHidden &&
                                                isSwipeTowardEdge(
                                                    activeEdge,
                                                    totalDelta,
                                                    swipeThreshold,
                                                )
                                        ) {
                                            tuckedBySwipe = true
                                            hidden = true
                                            event.changes.forEach { it.consume() }
                                        } else if (tuckedBySwipe) {
                                            event.changes.forEach { it.consume() }
                                        }
                                    } else {
                                        break
                                    }
                                }
                            } finally {
                                touching = false
                                if (!tuckedBySwipe) interaction++
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
        var handleTouched by remember { mutableStateOf(false) }
        var handleDimmed by remember { mutableStateOf(false) }
        LaunchedEffect(hidden, edge, handleTouched) {
            handleDimmed = false
            if (!hidden || edge == null || handleTouched) return@LaunchedEffect
            delay(FloatingTabBarHandleDimDelayMillis)
            handleDimmed = true
        }
        val handleAlpha by
            animateFloatAsState(
                if (handleDimmed) FloatingTabBarDimmedHandleAlpha else 1f,
                if (motion) tween(260) else snap(),
                label = "Floating tab expand alpha",
            )
        AnimatedVisibility(
            visible = hidden && edge != null,
            enter = edgeEnter(renderEdge, motion),
            exit = edgeExit(renderEdge, motion),
            // The offset must sit on the visibility node itself, or the button's hit area stays at
            // the corner while its content is drawn at the edge.
            modifier = Modifier.offset { expandOffset },
        ) {
            val shape = renderEdge.shape(expandShort)
            Box(
                Modifier.size(expandWidth, expandHeight)
                    .then(if (!renderEdge.vertical) Modifier.systemGestureExclusion() else Modifier)
                    .graphicsLayer { alpha = handleAlpha }
                    .shadow(2.dp, shape, clip = false)
                    .clip(shape)
                    .background(scheme.surface)
                    .pointerInput(renderEdge) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            handleTouched = true
                            var totalDelta = Offset.Zero
                            var dragged = false
                            var revealed = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id }
                                            ?: event.changes.firstOrNull()
                                    if (change == null) break
                                    if (!change.pressed) {
                                        if (!revealed && !dragged && change.changedToUp()) {
                                            interaction++
                                            hidden = false
                                        }
                                        change.consume()
                                        break
                                    }
                                    totalDelta += change.position - change.previousPosition
                                    if (
                                        !dragged &&
                                            totalDelta.getDistance() > viewConfiguration.touchSlop
                                    ) {
                                        dragged = true
                                    }
                                    if (dragged) {
                                        change.consume()
                                        if (
                                            !revealed &&
                                                isSwipeAwayFromEdge(
                                                    renderEdge,
                                                    totalDelta,
                                                    swipeThreshold,
                                                )
                                        ) {
                                            revealed = true
                                            interaction++
                                            hidden = false
                                        }
                                    }
                                }
                            } finally {
                                handleTouched = false
                            }
                        }
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = showDescription
                        onClick {
                            interaction++
                            hidden = false
                            true
                        }
                    }
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
