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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import kotlin.math.roundToInt

/** Maximum number of interactive cards fanned out; extra items stack on either side. */
internal const val TAB_STACK_FAN_LIMIT = 3

/** Maximum number of visible backing card layers rendered in a side stack. */
private const val TAB_STACK_SIDE_LAYERS = 3

/** Maximum double-tap recognition window so single-tap stack expansion stays snappy. */
private const val FAN_DOUBLE_TAP_WINDOW_MS = 180L

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

private enum class FanTapAction {
    NONE,
    PAGE_BACKWARD,
    PAGE_FORWARD,
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
 * single-tapping a stacked slot expands that stack and moves the previously shown cards to stack on
 * the opposite side; double-tapping any card selects it. Taps between the fan's outermost point
 * (plus margin) and the tab bar's outer edge stay inside [FanLayout.safeZone] and do not dismiss.
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
    marquee: RainbowMarquee? = null,
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
                        stackStepPrimaryPx = 5.dp.toPx(),
                        stackStepSecondaryPx = 2.dp.toPx(),
                        safePadPx = 28.dp.toPx(),
                        barBounds = localBar,
                    )
                }
            } else null

        val currentDismiss by rememberUpdatedState(onDismiss)
        if (active && layout != null) {
            Box(
                Modifier.fillMaxSize()
                    .testTag("Tab stack fan scrim")
                    .pointerInput(active, layout.safeZone) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            if (layout.safeZone.contains(down.position)) {
                                // The bar itself is layered above this scrim; consume taps on the
                                // remaining safe-zone gap so they stay open without leaking through
                                // to the terminal.
                                down.consume()
                                waitForUpOrCancellation()?.consume()
                                return@awaitEachGesture
                            }
                            down.consume()
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                currentDismiss()
                            }
                        }
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
            val settledMarquee = remember {
                RainbowMarquee(
                    mutableStateOf(0f),
                    mutableStateOf(0f),
                )
            }
            val scheme = MaterialTheme.colorScheme
            val cardShape = RoundedCornerShape(9.dp)
            val spatialSpec = if (motion) RexTokens.motion.fastSpatial<Float>() else snap()
            val effectSpec = if (motion) RexTokens.motion.fastEffects<Float>() else snap()
            val hasLeading = layout.windowStart > 0
            val hasTrailing = layout.visibleEnd < displayItems.size
            val leadingHead = displayItems.getOrNull(layout.windowStart)
            val trailingHead = displayItems.getOrNull(layout.visibleEnd - 1)
            val currentItemCount by rememberUpdatedState(displayItems.size)
            val currentWindowStart by rememberUpdatedState(layout.windowStart)
            val currentSelect by rememberUpdatedState(onSelect)
            fun pageBackward() {
                windowStart = (currentWindowStart - TAB_STACK_FAN_LIMIT).coerceAtLeast(0)
            }
            fun pageForward() {
                val maxStart = (currentItemCount - TAB_STACK_FAN_LIMIT).coerceAtLeast(0)
                windowStart = (currentWindowStart + TAB_STACK_FAN_LIMIT).coerceAtMost(maxStart)
            }

            Box(Modifier.fillMaxSize().testTag("Tab stack fan")) {
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
                        val tapTargetId =
                            when (pose.role) {
                                FanCardRole.PRIMARY -> item.id
                                FanCardRole.LEADING_STACK -> leadingHead?.id ?: item.id
                                FanCardRole.TRAILING_STACK -> trailingHead?.id ?: item.id
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
                        val currentTargetId by rememberUpdatedState(tapTargetId)
                        fun invokeSingleTap() {
                            when (tapAction) {
                                FanTapAction.PAGE_BACKWARD -> pageBackward()
                                FanTapAction.PAGE_FORWARD -> pageForward()
                                FanTapAction.NONE -> Unit
                            }
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
                                                            invokeSingleTap()
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
                                                invokeSingleTap()
                                                true
                                            }
                                        }
                                    }
                                }
                                .then(
                                    if (active && pose.visibleInStack) {
                                        Modifier.pointerInput(Unit) {
                                            val timeoutMs =
                                                minOf(
                                                    viewConfiguration.doubleTapTimeoutMillis,
                                                    FAN_DOUBLE_TAP_WINDOW_MS,
                                                )
                                            awaitEachGesture {
                                                val down1 =
                                                    awaitFirstDown(requireUnconsumed = false)
                                                down1.consume()
                                                val up1 =
                                                    waitForUpOrCancellation()
                                                        ?: return@awaitEachGesture
                                                up1.consume()
                                                val down2 =
                                                    withTimeoutOrNull(timeoutMs) {
                                                        awaitFirstDown(requireUnconsumed = false)
                                                    }
                                                if (down2 == null) {
                                                    invokeSingleTap()
                                                } else {
                                                    down2.consume()
                                                    val up2 = waitForUpOrCancellation()
                                                    if (up2 != null) {
                                                        up2.consume()
                                                        currentSelect(currentTargetId)
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val tileModifier =
                                Modifier.size(cardWidth, cardHeight)
                                    .shadow(
                                        if (isPrimary) 4.dp else 2.dp,
                                        cardShape,
                                        clip = false,
                                    )
                                    .then(
                                        if (item.selected && isPrimary)
                                            Modifier.border(1.5.dp, scheme.roles.action, cardShape)
                                        else Modifier,
                                    )
                            PaneProgramTile(
                                program = item.program,
                                modifier = tileModifier,
                                large = true,
                                rotation = 0f,
                                marquee =
                                    when {
                                        !item.working && item.progress?.busy != true -> null
                                        !isPrimary -> settledMarquee
                                        else -> marquee
                                    },
                                progress = if (isPrimary) item.progress else null,
                            )
                        }
                    }
                }
            }
        }
    }
}
