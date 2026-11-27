package dev.lemon.rex

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt

/** Maximum number of interactive cards fanned out; extra items stack on either side. */
internal const val TAB_STACK_FAN_LIMIT = 3

/** Maximum number of visible backing card layers rendered in a side stack. */
private const val TAB_STACK_SIDE_LAYERS = 3

/** Minimum drag distance along the spread axis to flip pages on the fan. */
private val FanSwipeThreshold = 24.dp

/** Extra outward hit extension beyond the outer primary card for single-tapping side stacks. */
private val FanStackHitExtension = 36.dp

private val FanSelectedBorderWidth = 2.5.dp

/**
 * Mac-style neutral selection ring: cool medium gray in light themes, soft white in dark themes.
 */
internal fun fanSelectionBorderColor(scheme: ColorScheme): Color =
    if (scheme.surface.luminance() < .5f) tintOver(Color.White, scheme.surface, .90f)
    else tintOver(scheme.onSurfaceVariant, scheme.surface, .82f)

internal data class TabFanItem(
    val id: String,
    val program: ProgramIdentity,
    val title: String = program.label,
    val selected: Boolean = false,
    val working: Boolean = false,
    val progress: TerminalProgress? = null,
)

internal enum class FanDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

internal enum class FanCardRole {
    LEADING_STACK,
    PRIMARY,
    TRAILING_STACK,
}

internal enum class FanTapAction {
    NONE,
    PAGE_BACKWARD,
    PAGE_FORWARD,
}

internal fun resolveFanSwipeAction(
    direction: FanDirection,
    delta: Offset,
    thresholdPx: Float,
): FanTapAction {
    val alongSpread: Float
    val crossSpread: Float
    when (direction) {
        FanDirection.UP,
        FanDirection.DOWN -> {
            alongSpread = delta.x
            crossSpread = abs(delta.y)
        }
        FanDirection.LEFT,
        FanDirection.RIGHT -> {
            // Trailing cards stack at the top (smaller y), leading cards at the bottom (larger y).
            alongSpread = -delta.y
            crossSpread = abs(delta.x)
        }
    }
    if (abs(alongSpread) < thresholdPx || abs(alongSpread) <= crossSpread) {
        return FanTapAction.NONE
    }
    return if (alongSpread < 0f) FanTapAction.PAGE_FORWARD else FanTapAction.PAGE_BACKWARD
}

private suspend fun PointerInputScope.detectFanGesture(
    requireUnconsumedDown: Boolean = false,
    direction: () -> FanDirection?,
    swipeThresholdPx: Float,
    shouldHandle: (Offset) -> Boolean = { true },
    onOutsideTap: () -> Unit = {},
    onPage: (FanTapAction) -> Unit,
    onTap: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = requireUnconsumedDown)
        down.consume()
        if (!shouldHandle(down.position)) {
            val up = waitForUpOrCancellation()
            if (up != null) {
                up.consume()
                onOutsideTap()
            }
            return@awaitEachGesture
        }
        var totalDelta = Offset.Zero
        var dragged = false
        var paged = false
        var lifted = false
        while (true) {
            val event = awaitPointerEvent()
            val change =
                event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
            if (change == null) break
            if (!change.pressed) {
                lifted = change.changedToUp()
                change.consume()
                break
            }
            totalDelta += change.position - change.previousPosition
            if (!dragged && totalDelta.getDistance() > viewConfiguration.touchSlop) {
                dragged = true
            }
            if (dragged) {
                change.consume()
                if (!paged) {
                    val action =
                        direction()?.let { resolveFanSwipeAction(it, totalDelta, swipeThresholdPx) }
                            ?: FanTapAction.NONE
                    if (action != FanTapAction.NONE) {
                        paged = true
                        onPage(action)
                    }
                }
            }
        }
        if (!dragged && lifted) onTap()
    }
}

internal data class FanCardPose(
    val itemIndex: Int,
    val role: FanCardRole,
    val centerX: Float,
    val centerY: Float,
    val rotation: Float,
    val scale: Float,
    val zIndex: Float,
    val visibleInStack: Boolean = true,
)

internal data class FanLayout(
    val direction: FanDirection,
    val windowStart: Int,
    val visibleEnd: Int,
    val cards: List<FanCardPose>,
    val safeZone: Rect,
)

/**
 * Picks the direction with the clearest open workspace around [anchor]. When [preferredAxis] is
 * supplied (e.g. a horizontal bar vs. a vertical side rail), the fan stays perpendicular to the bar
 * so it never collides with neighbouring tabs.
 */
internal fun resolveFanDirection(
    anchor: Rect,
    workspace: Size,
    preferredAxis: Orientation? = null,
): FanDirection {
    val topSpace = anchor.top.coerceAtLeast(0f)
    val bottomSpace = (workspace.height - anchor.bottom).coerceAtLeast(0f)
    val leftSpace = anchor.left.coerceAtLeast(0f)
    val rightSpace = (workspace.width - anchor.right).coerceAtLeast(0f)
    return when (preferredAxis) {
        Orientation.Horizontal ->
            if (topSpace >= bottomSpace) FanDirection.UP else FanDirection.DOWN
        Orientation.Vertical ->
            if (rightSpace >= leftSpace) FanDirection.RIGHT else FanDirection.LEFT
        null ->
            when {
                topSpace >= bottomSpace && topSpace >= leftSpace && topSpace >= rightSpace ->
                    FanDirection.UP
                rightSpace >= leftSpace && rightSpace >= bottomSpace -> FanDirection.RIGHT
                leftSpace >= bottomSpace -> FanDirection.LEFT
                else -> FanDirection.DOWN
            }
    }
}

/**
 * Computes a folding-fan arrangement for up to 3 primary cards starting at [windowStart]. Any
 * trailing items stack tightly behind the right-most / top-most primary card, and previously
 * displayed items stack tightly behind the opposite (left-most / bottom-most) primary card.
 *
 * Also computes [FanLayout.safeZone] spanning from the fan's outermost point (plus [safePadPx])
 * through the outer edge of [barBounds] (or [anchor] when [barBounds] is empty).
 */
internal fun computeFanLayout(
    itemCount: Int,
    windowStart: Int,
    anchor: Rect,
    workspace: Size,
    direction: FanDirection,
    cardSize: Size,
    marginPx: Float,
    gapPx: Float,
    spreadStepPx: Float,
    archPx: Float,
    stackStepPrimaryPx: Float,
    stackStepSecondaryPx: Float,
    safePadPx: Float = 28f,
    barBounds: Rect = anchor,
): FanLayout {
    if (itemCount <= 0) return FanLayout(direction, 0, 0, emptyList(), Rect.Zero)
    val maxStart = (itemCount - TAB_STACK_FAN_LIMIT).coerceAtLeast(0)
    val clampedStart = windowStart.coerceIn(0, maxStart)
    val visibleEnd = minOf(itemCount, clampedStart + TAB_STACK_FAN_LIMIT)
    val visibleCount = visibleEnd - clampedStart
    val slots =
        when (visibleCount) {
            1 -> listOf(0f)
            2 -> listOf(-0.55f, 0.55f)
            else -> listOf(-1f, 0f, 1f)
        }
    val anchorCenterX = anchor.center.x
    val anchorCenterY = anchor.center.y
    val maxFanAngle = if (visibleCount == 2) 10f else 14f
    val leadingOnly = clampedStart > 0 && visibleEnd >= itemCount

    val rawPrimary = slots.mapIndexed { slot, t ->
        val arch = 1f - t * t
        val index = clampedStart + slot
        // Keep the stacked outer slot on top of its fanned neighbours on either side.
        val primaryZ = if (leadingOnly) (visibleCount - slot).toFloat() else (slot + 1).toFloat()
        when (direction) {
            FanDirection.UP ->
                FanCardPose(
                    itemIndex = index,
                    role = FanCardRole.PRIMARY,
                    centerX = anchorCenterX + t * spreadStepPx,
                    centerY = anchor.top - cardSize.height / 2f - gapPx - arch * archPx,
                    rotation = t * maxFanAngle,
                    scale = 1f,
                    zIndex = primaryZ,
                )
            FanDirection.DOWN ->
                FanCardPose(
                    itemIndex = index,
                    role = FanCardRole.PRIMARY,
                    centerX = anchorCenterX + t * spreadStepPx,
                    centerY = anchor.bottom + cardSize.height / 2f + gapPx + arch * archPx,
                    rotation = -t * maxFanAngle,
                    scale = 1f,
                    zIndex = primaryZ,
                )
            FanDirection.RIGHT ->
                FanCardPose(
                    itemIndex = index,
                    role = FanCardRole.PRIMARY,
                    centerX = anchor.right + cardSize.width / 2f + gapPx + arch * archPx,
                    centerY = anchorCenterY - t * spreadStepPx,
                    rotation = -t * maxFanAngle,
                    scale = 1f,
                    zIndex = primaryZ,
                )
            FanDirection.LEFT ->
                FanCardPose(
                    itemIndex = index,
                    role = FanCardRole.PRIMARY,
                    centerX = anchor.left - cardSize.width / 2f - gapPx - arch * archPx,
                    centerY = anchorCenterY - t * spreadStepPx,
                    rotation = t * maxFanAngle,
                    scale = 1f,
                    zIndex = primaryZ,
                )
        }
    }

    val first = rawPrimary.first()
    val last = rawPrimary.last()

    // Previously shown cards stack tightly behind the first primary card on the opposite side.
    val rawLeading =
        (0 until clampedStart).map { index ->
            val layer = clampedStart - 1 - index
            val visualLayer = layer.coerceAtMost(TAB_STACK_SIDE_LAYERS - 1)
            val offset = (visualLayer + 1) * stackStepPrimaryPx
            val secondary = (visualLayer + 1) * stackStepSecondaryPx
            val scale = (0.95f - visualLayer * 0.04f).coerceAtLeast(0.85f)
            val visibleInStack = layer < TAB_STACK_SIDE_LAYERS
            when (direction) {
                FanDirection.UP ->
                    FanCardPose(
                        itemIndex = index,
                        role = FanCardRole.LEADING_STACK,
                        centerX = first.centerX - offset,
                        centerY = first.centerY + secondary,
                        rotation = first.rotation - (visualLayer + 1) * 4f,
                        scale = scale,
                        zIndex = -(layer + 1).toFloat(),
                        visibleInStack = visibleInStack,
                    )
                FanDirection.DOWN ->
                    FanCardPose(
                        itemIndex = index,
                        role = FanCardRole.LEADING_STACK,
                        centerX = first.centerX - offset,
                        centerY = first.centerY - secondary,
                        rotation = first.rotation + (visualLayer + 1) * 4f,
                        scale = scale,
                        zIndex = -(layer + 1).toFloat(),
                        visibleInStack = visibleInStack,
                    )
                FanDirection.RIGHT ->
                    FanCardPose(
                        itemIndex = index,
                        role = FanCardRole.LEADING_STACK,
                        centerX = first.centerX - secondary,
                        centerY = first.centerY + offset,
                        rotation = first.rotation + (visualLayer + 1) * 4f,
                        scale = scale,
                        zIndex = -(layer + 1).toFloat(),
                        visibleInStack = visibleInStack,
                    )
                FanDirection.LEFT ->
                    FanCardPose(
                        itemIndex = index,
                        role = FanCardRole.LEADING_STACK,
                        centerX = first.centerX + secondary,
                        centerY = first.centerY + offset,
                        rotation = first.rotation - (visualLayer + 1) * 4f,
                        scale = scale,
                        zIndex = -(layer + 1).toFloat(),
                        visibleInStack = visibleInStack,
                    )
            }
        }

    // Remaining cards stack tightly behind the right-most (UP/DOWN) or top-most (LEFT/RIGHT) card.
    val rawTrailing =
        (visibleEnd until itemCount).map { index ->
            val layer = index - visibleEnd
            val visualLayer = layer.coerceAtMost(TAB_STACK_SIDE_LAYERS - 1)
            val offset = (visualLayer + 1) * stackStepPrimaryPx
            val secondary = (visualLayer + 1) * stackStepSecondaryPx
            val scale = (0.95f - visualLayer * 0.04f).coerceAtLeast(0.85f)
            val visibleInStack = layer < TAB_STACK_SIDE_LAYERS
            when (direction) {
                FanDirection.UP ->
                    FanCardPose(
                        itemIndex = index,
                        role = FanCardRole.TRAILING_STACK,
                        centerX = last.centerX + offset,
                        centerY = last.centerY + secondary,
                        rotation = last.rotation + (visualLayer + 1) * 4f,
                        scale = scale,
                        zIndex = -(layer + 1).toFloat(),
                        visibleInStack = visibleInStack,
                    )
                FanDirection.DOWN ->
                    FanCardPose(
                        itemIndex = index,
                        role = FanCardRole.TRAILING_STACK,
                        centerX = last.centerX + offset,
                        centerY = last.centerY - secondary,
                        rotation = last.rotation - (visualLayer + 1) * 4f,
                        scale = scale,
                        zIndex = -(layer + 1).toFloat(),
                        visibleInStack = visibleInStack,
                    )
                FanDirection.RIGHT ->
                    FanCardPose(
                        itemIndex = index,
                        role = FanCardRole.TRAILING_STACK,
                        centerX = last.centerX - secondary,
                        centerY = last.centerY - offset,
                        rotation = last.rotation - (visualLayer + 1) * 4f,
                        scale = scale,
                        zIndex = -(layer + 1).toFloat(),
                        visibleInStack = visibleInStack,
                    )
                FanDirection.LEFT ->
                    FanCardPose(
                        itemIndex = index,
                        role = FanCardRole.TRAILING_STACK,
                        centerX = last.centerX + secondary,
                        centerY = last.centerY - offset,
                        rotation = last.rotation + (visualLayer + 1) * 4f,
                        scale = scale,
                        zIndex = -(layer + 1).toFloat(),
                        visibleInStack = visibleInStack,
                    )
            }
        }

    val combined = rawLeading + rawPrimary + rawTrailing
    val visibleForBounds = combined.filter { it.visibleInStack }
    val halfW = cardSize.width / 2f
    val halfH = cardSize.height / 2f
    val minLeft = visibleForBounds.minOf { it.centerX - halfW * it.scale }
    val maxRight = visibleForBounds.maxOf { it.centerX + halfW * it.scale }
    val minTop = visibleForBounds.minOf { it.centerY - halfH * it.scale }
    val maxBottom = visibleForBounds.maxOf { it.centerY + halfH * it.scale }

    val clampedLeft = marginPx
    val clampedRight = (workspace.width - marginPx).coerceAtLeast(clampedLeft)
    val clampedTop = marginPx
    val clampedBottom = (workspace.height - marginPx).coerceAtLeast(clampedTop)

    val shiftX =
        when {
            maxRight - minLeft > clampedRight - clampedLeft ->
                (clampedLeft + clampedRight) / 2f - (minLeft + maxRight) / 2f
            minLeft < clampedLeft -> clampedLeft - minLeft
            maxRight > clampedRight -> clampedRight - maxRight
            else -> 0f
        }
    val shiftY =
        when {
            maxBottom - minTop > clampedBottom - clampedTop ->
                (clampedTop + clampedBottom) / 2f - (minTop + maxBottom) / 2f
            minTop < clampedTop -> clampedTop - minTop
            maxBottom > clampedBottom -> clampedBottom - maxBottom
            else -> 0f
        }

    val shiftedCards = combined.map {
        it.copy(centerX = it.centerX + shiftX, centerY = it.centerY + shiftY)
    }
    val shiftedVisible = shiftedCards.filter { it.visibleInStack }
    val cardsTop = shiftedVisible.minOf { it.centerY - halfH * it.scale }
    val cardsBottom = shiftedVisible.maxOf { it.centerY + halfH * it.scale }
    val cardsLeft = shiftedVisible.minOf { it.centerX - halfW * it.scale }
    val cardsRight = shiftedVisible.maxOf { it.centerX + halfW * it.scale }

    val bar = if (barBounds.isEmpty) anchor else barBounds
    val safeLeftBase = (minOf(cardsLeft, bar.left, anchor.left) - safePadPx).coerceAtLeast(0f)
    val safeRightBase =
        (maxOf(cardsRight, bar.right, anchor.right) + safePadPx).coerceAtMost(workspace.width)
    val safeTopBase = (minOf(cardsTop, bar.top, anchor.top) - safePadPx).coerceAtLeast(0f)
    val safeBottomBase =
        (maxOf(cardsBottom, bar.bottom, anchor.bottom) + safePadPx).coerceAtMost(workspace.height)

    val safeZone =
        when (direction) {
            FanDirection.UP ->
                Rect(
                    safeLeftBase,
                    safeTopBase,
                    safeRightBase,
                    maxOf(bar.bottom, anchor.bottom).coerceAtMost(workspace.height),
                )
            FanDirection.DOWN ->
                Rect(
                    safeLeftBase,
                    minOf(bar.top, anchor.top).coerceAtLeast(0f),
                    safeRightBase,
                    safeBottomBase,
                )
            FanDirection.RIGHT ->
                Rect(
                    minOf(bar.left, anchor.left).coerceAtLeast(0f),
                    safeTopBase,
                    safeRightBase,
                    safeBottomBase,
                )
            FanDirection.LEFT ->
                Rect(
                    safeLeftBase,
                    safeTopBase,
                    maxOf(bar.right, anchor.right).coerceAtMost(workspace.width),
                    safeBottomBase,
                )
        }

    return FanLayout(
        direction = direction,
        windowStart = clampedStart,
        visibleEnd = visibleEnd,
        cards = shiftedCards,
        safeZone = safeZone,
    )
}

/**
 * Anchored folding-fan card selector that pops out from a stacked tab anchor into open workspace
 * space. Displays up to 3 fanned cards while stacking extra cards tightly at the outer slot:
 * single-tapping any primary card selects it immediately; swiping along the fan's spread axis or
 * single-tapping a stacked edge slot pages through overflow cards. Taps between the fan's outermost
 * point (plus margin) and the tab bar's outer edge stay inside [FanLayout.safeZone] and do not
 * dismiss.
 */
@Composable
internal fun TabStackFan(
    visible: Boolean,
    items: List<TabFanItem>,
    anchorBounds: Rect,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    barBounds: Rect = anchorBounds,
    preferredAxis: Orientation? = null,
    bar: @Composable () -> Unit,
) {
    val active = visible && items.size > 1 && !anchorBounds.isEmpty
    val motion = LocalMotionEnabled.current
    val progress = remember { Animatable(0f) }
    var cachedItems by remember { mutableStateOf<List<TabFanItem>>(emptyList()) }
    var cachedAnchor by remember { mutableStateOf(Rect.Zero) }
    var cachedBar by remember { mutableStateOf(Rect.Zero) }
    if (active) {
        cachedItems = items
        cachedAnchor = anchorBounds
        cachedBar = barBounds
    }
    val displayItems = if (active) items else cachedItems
    val displayAnchor = if (active) anchorBounds else cachedAnchor
    val displayBar = if (active) barBounds else cachedBar
    var windowStart by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible) { if (visible) windowStart = 0 }
    LaunchedEffect(displayItems.size) {
        val maxStart = (displayItems.size - TAB_STACK_FAN_LIMIT).coerceAtLeast(0)
        if (windowStart > maxStart) windowStart = maxStart
    }
    LaunchedEffect(active) {
        if (active) {
            if (motion) progress.animateTo(1f, RexTokens.motion.fastSpatial())
            else progress.snapTo(1f)
        } else {
            if (motion) progress.animateTo(0f, RexTokens.motion.fastEffects())
            else progress.snapTo(0f)
            cachedItems = emptyList()
        }
    }
    BackHandler(enabled = active) { onDismiss() }

    val dismissFanDescription = stringResource(R.string.a11y_fan_dismiss)
    val nextPanesDescription = stringResource(R.string.a11y_fan_next_panes)
    val previousPanesDescription = stringResource(R.string.a11y_fan_previous_panes)
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier.fillMaxSize().onGloballyPositioned { containerOrigin = it.boundsInRoot().topLeft },
    ) {
        val localAnchor = displayAnchor.translate(-containerOrigin.x, -containerOrigin.y)
        val localBar =
            if (displayBar.isEmpty) localAnchor
            else displayBar.translate(-containerOrigin.x, -containerOrigin.y)
        val density = LocalDensity.current
        val cardWidth = 48.dp
        val cardHeight = 38.dp
        val touchSize = 48.dp
        val cardSizePx = with(density) { Size(cardWidth.toPx(), cardHeight.toPx()) }
        val touchSizePx = with(density) { touchSize.toPx() }
        val swipeThresholdPx = with(density) { FanSwipeThreshold.toPx() }
        val stackHitExtensionPx = with(density) { FanStackHitExtension.toPx() }
        val workspaceSize = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val showFan =
            active || (progress.value > 0.005f && displayItems.size > 1 && !displayAnchor.isEmpty)
        val layout =
            if (showFan) {
                val direction = resolveFanDirection(localAnchor, workspaceSize, preferredAxis)
                with(density) {
                    computeFanLayout(
                        itemCount = displayItems.size,
                        windowStart = windowStart,
                        anchor = localAnchor,
                        workspace = workspaceSize,
                        direction = direction,
                        cardSize = cardSizePx,
                        marginPx = 8.dp.toPx(),
                        gapPx = 12.dp.toPx(),
                        spreadStepPx = 50.dp.toPx(),
                        archPx = 10.dp.toPx(),
                        stackStepPrimaryPx = 7.dp.toPx(),
                        stackStepSecondaryPx = 2.dp.toPx(),
                        safePadPx = 28.dp.toPx(),
                        barBounds = localBar,
                    )
                }
            } else null

        val currentDismiss by rememberUpdatedState(onDismiss)
        val currentDirection by rememberUpdatedState(layout?.direction)
        val currentSafeZone by rememberUpdatedState(layout?.safeZone ?: Rect.Zero)
        val currentHasLeading by rememberUpdatedState((layout?.windowStart ?: 0) > 0)
        val currentHasTrailing by
            rememberUpdatedState(
                layout != null && layout.visibleEnd < displayItems.size,
            )
        val currentItemCount by rememberUpdatedState(displayItems.size)
        val currentWindowStart by rememberUpdatedState(layout?.windowStart ?: 0)
        val currentSelect by rememberUpdatedState(onSelect)
        fun pageBackward() {
            windowStart = (currentWindowStart - TAB_STACK_FAN_LIMIT).coerceAtLeast(0)
        }
        fun pageForward() {
            val maxStart = (currentItemCount - TAB_STACK_FAN_LIMIT).coerceAtLeast(0)
            windowStart = (currentWindowStart + TAB_STACK_FAN_LIMIT).coerceAtMost(maxStart)
        }
        fun performPage(action: FanTapAction) {
            when (action) {
                FanTapAction.PAGE_FORWARD -> if (currentHasTrailing) pageForward()
                FanTapAction.PAGE_BACKWARD -> if (currentHasLeading) pageBackward()
                FanTapAction.NONE -> Unit
            }
        }
        if (active && layout != null) {
            Box(
                Modifier.fillMaxSize()
                    .testTag("Tab stack fan scrim")
                    .pointerInput(active) {
                        detectFanGesture(
                            requireUnconsumedDown = true,
                            direction = { currentDirection },
                            swipeThresholdPx = swipeThresholdPx,
                            shouldHandle = { currentSafeZone.contains(it) },
                            onOutsideTap = { currentDismiss() },
                            onPage = ::performPage,
                            onTap = {},
                        )
                    }
                    .semantics {
                        contentDescription = dismissFanDescription
                        onClick {
                            currentDismiss()
                            true
                        }
                    },
            )
        }
        // The bar sits above the dismiss scrim and below the cards, so its tabs, grip and
        // actions keep their own pointer input while the fan is open.
        bar()
        if (layout != null) {
            val t = progress.value.coerceIn(0f, 1f)
            val anchorCenterX = localAnchor.center.x
            val anchorCenterY = localAnchor.center.y
            val scheme = MaterialTheme.colorScheme
            val assets = LocalContext.current.assets
            val pendingCardShape = RoundedCornerShape(7.dp)
            val selectedBorderColor = fanSelectionBorderColor(scheme)
            val spatialSpec = if (motion) RexTokens.motion.fastSpatial<Float>() else snap()
            val effectSpec = if (motion) RexTokens.motion.fastEffects<Float>() else snap()
            val hasLeading = layout.windowStart > 0
            val hasTrailing = layout.visibleEnd < displayItems.size
            val firstPrimaryPose = layout.cards.firstOrNull { it.role == FanCardRole.PRIMARY }
            val lastPrimaryPose = layout.cards.lastOrNull { it.role == FanCardRole.PRIMARY }

            Box(Modifier.fillMaxSize().testTag("Tab stack fan")) {
                if (active && firstPrimaryPose != null && hasLeading) {
                    val isHorizontal =
                        layout.direction == FanDirection.UP || layout.direction == FanDirection.DOWN
                    val padLeft: Float
                    val padTop: Float
                    val padWidthPx: Float
                    val padHeightPx: Float
                    if (isHorizontal) {
                        val right = firstPrimaryPose.centerX - touchSizePx / 2f
                        padLeft = (right - stackHitExtensionPx).coerceAtLeast(0f)
                        padTop =
                            (firstPrimaryPose.centerY - touchSizePx / 2f).coerceIn(
                                0f,
                                (workspaceSize.height - touchSizePx).coerceAtLeast(0f),
                            )
                        padWidthPx = (right - padLeft).coerceAtLeast(0f)
                        padHeightPx = touchSizePx
                    } else {
                        padLeft =
                            (firstPrimaryPose.centerX - touchSizePx / 2f).coerceIn(
                                0f,
                                (workspaceSize.width - touchSizePx).coerceAtLeast(0f),
                            )
                        padTop =
                            (firstPrimaryPose.centerY + touchSizePx / 2f).coerceIn(
                                0f,
                                workspaceSize.height,
                            )
                        padWidthPx = touchSizePx
                        padHeightPx =
                            minOf(
                                stackHitExtensionPx,
                                (workspaceSize.height - padTop).coerceAtLeast(0f),
                            )
                    }
                    if (padWidthPx > 0f && padHeightPx > 0f) {
                        Box(
                            Modifier.offset { IntOffset(padLeft.roundToInt(), padTop.roundToInt()) }
                                .size(
                                    with(density) { padWidthPx.toDp() },
                                    with(density) { padHeightPx.toDp() },
                                )
                                .zIndex(-10f)
                                .testTag("Tab fan leading hit")
                                .pointerInput(Unit) {
                                    detectFanGesture(
                                        direction = { currentDirection },
                                        swipeThresholdPx = swipeThresholdPx,
                                        onPage = ::performPage,
                                        onTap = { performPage(FanTapAction.PAGE_BACKWARD) },
                                    )
                                },
                        )
                    }
                }
                if (active && lastPrimaryPose != null && hasTrailing) {
                    val isHorizontal =
                        layout.direction == FanDirection.UP || layout.direction == FanDirection.DOWN
                    val padLeft: Float
                    val padTop: Float
                    val padWidthPx: Float
                    val padHeightPx: Float
                    if (isHorizontal) {
                        padLeft =
                            (lastPrimaryPose.centerX + touchSizePx / 2f).coerceIn(
                                0f,
                                workspaceSize.width,
                            )
                        padTop =
                            (lastPrimaryPose.centerY - touchSizePx / 2f).coerceIn(
                                0f,
                                (workspaceSize.height - touchSizePx).coerceAtLeast(0f),
                            )
                        padWidthPx =
                            minOf(
                                stackHitExtensionPx,
                                (workspaceSize.width - padLeft).coerceAtLeast(0f),
                            )
                        padHeightPx = touchSizePx
                    } else {
                        padLeft =
                            (lastPrimaryPose.centerX - touchSizePx / 2f).coerceIn(
                                0f,
                                (workspaceSize.width - touchSizePx).coerceAtLeast(0f),
                            )
                        val bottom = lastPrimaryPose.centerY - touchSizePx / 2f
                        padTop = (bottom - stackHitExtensionPx).coerceAtLeast(0f)
                        padWidthPx = touchSizePx
                        padHeightPx = (bottom - padTop).coerceAtLeast(0f)
                    }
                    if (padWidthPx > 0f && padHeightPx > 0f) {
                        Box(
                            Modifier.offset { IntOffset(padLeft.roundToInt(), padTop.roundToInt()) }
                                .size(
                                    with(density) { padWidthPx.toDp() },
                                    with(density) { padHeightPx.toDp() },
                                )
                                .zIndex(-10f)
                                .testTag("Tab fan trailing hit")
                                .pointerInput(Unit) {
                                    detectFanGesture(
                                        direction = { currentDirection },
                                        swipeThresholdPx = swipeThresholdPx,
                                        onPage = ::performPage,
                                        onTap = { performPage(FanTapAction.PAGE_FORWARD) },
                                    )
                                },
                        )
                    }
                }
                for (pose in layout.cards) {
                    val item = displayItems.getOrNull(pose.itemIndex) ?: continue
                    key(item.id) {
                        val animX by
                            animateFloatAsState(
                                pose.centerX,
                                spatialSpec,
                                label = "Fan card x",
                            )
                        val animY by
                            animateFloatAsState(
                                pose.centerY,
                                spatialSpec,
                                label = "Fan card y",
                            )
                        val animRot by
                            animateFloatAsState(
                                pose.rotation,
                                spatialSpec,
                                label = "Fan card rot",
                            )
                        val animScale by
                            animateFloatAsState(
                                pose.scale,
                                spatialSpec,
                                label = "Fan card scale",
                            )
                        val animVisibility by
                            animateFloatAsState(
                                if (pose.visibleInStack) 1f else 0f,
                                effectSpec,
                                label = "Fan card alpha",
                            )

                        val currentX = lerp(anchorCenterX, animX, t)
                        val currentY = lerp(anchorCenterY, animY, t)
                        val originRotation =
                            when {
                                pose.role != FanCardRole.PRIMARY -> 6f
                                pose.itemIndex == layout.windowStart -> -6f
                                else -> 3f * (pose.itemIndex - layout.windowStart)
                            }
                        val currentRotation = lerp(originRotation, animRot, t)
                        val currentScale = lerp(0.55f, animScale, t)
                        val currentAlpha = (t / 0.35f).coerceIn(0f, 1f) * animVisibility
                        val isPrimary = pose.role == FanCardRole.PRIMARY
                        val isLeadingHead =
                            isPrimary && hasLeading && pose.itemIndex == layout.windowStart
                        val isTrailingHead =
                            isPrimary && hasTrailing && pose.itemIndex == layout.visibleEnd - 1
                        val tag =
                            when (pose.role) {
                                FanCardRole.PRIMARY -> "Tab fan card ${item.id}"
                                FanCardRole.LEADING_STACK -> "Tab fan leading ${item.id}"
                                FanCardRole.TRAILING_STACK -> "Tab fan overflow ${item.id}"
                            }
                        val tapAction by
                            rememberUpdatedState(
                                when {
                                    pose.role == FanCardRole.LEADING_STACK || isLeadingHead ->
                                        FanTapAction.PAGE_BACKWARD
                                    pose.role == FanCardRole.TRAILING_STACK || isTrailingHead ->
                                        FanTapAction.PAGE_FORWARD
                                    else -> FanTapAction.NONE
                                },
                            )
                        val currentIsPrimary by rememberUpdatedState(isPrimary)
                        val currentRole by rememberUpdatedState(pose.role)
                        fun invokePageAction() {
                            performPage(tapAction)
                        }

                        Box(
                            Modifier.offset {
                                    IntOffset(
                                        (currentX - touchSizePx / 2f).roundToInt(),
                                        (currentY - touchSizePx / 2f).roundToInt(),
                                    )
                                }
                                .size(touchSize)
                                .zIndex(pose.zIndex)
                                .graphicsLayer {
                                    rotationZ = currentRotation
                                    scaleX = currentScale
                                    scaleY = currentScale
                                    alpha = currentAlpha
                                }
                                .testTag(tag)
                                .semantics {
                                    role = Role.Button
                                    if (isPrimary) {
                                        contentDescription = item.title
                                        selected = item.selected
                                        if (active && pose.visibleInStack) {
                                            onClick {
                                                currentSelect(item.id)
                                                true
                                            }
                                            if (tapAction != FanTapAction.NONE) {
                                                val pageLabel =
                                                    if (tapAction == FanTapAction.PAGE_BACKWARD)
                                                        previousPanesDescription
                                                    else nextPanesDescription
                                                customActions =
                                                    listOf(
                                                        CustomAccessibilityAction(pageLabel) {
                                                            invokePageAction()
                                                            true
                                                        },
                                                    )
                                            }
                                        }
                                    } else {
                                        contentDescription =
                                            if (pose.role == FanCardRole.LEADING_STACK)
                                                previousPanesDescription
                                            else nextPanesDescription
                                        if (active && pose.visibleInStack) {
                                            onClick {
                                                invokePageAction()
                                                true
                                            }
                                        }
                                    }
                                }
                                .then(
                                    if (active && pose.visibleInStack) {
                                        Modifier.pointerInput(Unit) {
                                            detectFanGesture(
                                                direction = { currentDirection },
                                                swipeThresholdPx = swipeThresholdPx,
                                                onPage = ::performPage,
                                                onTap = {
                                                    if (currentIsPrimary) {
                                                        currentSelect(item.id)
                                                    } else if (
                                                        currentRole == FanCardRole.LEADING_STACK
                                                    ) {
                                                        performPage(FanTapAction.PAGE_BACKWARD)
                                                    } else {
                                                        performPage(FanTapAction.PAGE_FORWARD)
                                                    }
                                                },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val shadowShape =
                                if (item.program.pending) pendingCardShape
                                else ProgramTileShadowShape
                            val tileModifier =
                                Modifier.size(cardWidth, cardHeight)
                                    .shadow(
                                        if (isPrimary) 4.dp else 2.dp,
                                        shadowShape,
                                        clip = false,
                                    )
                                    .then(
                                        if (item.selected && isPrimary) {
                                            if (item.program.pending) {
                                                Modifier.border(
                                                    FanSelectedBorderWidth,
                                                    selectedBorderColor,
                                                    pendingCardShape,
                                                )
                                            } else {
                                                Modifier.programTileBorder(
                                                    assets = assets,
                                                    color = selectedBorderColor,
                                                    width = FanSelectedBorderWidth,
                                                    fallbackCornerRadius = 7.dp,
                                                )
                                            }
                                        } else Modifier,
                                    )
                            PaneProgramTile(
                                program = item.program,
                                modifier = tileModifier,
                                large = true,
                                rotation = 0f,
                                marquee = null,
                                progress = if (isPrimary) item.progress else null,
                            )
                        }
                    }
                }
            }
        }
    }
}
