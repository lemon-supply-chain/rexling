package dev.lemon.rex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val floatingFanEase = CubicBezierEasing(.22f, .61f, .36f, 1f)

private data class FloatingFanClip(val clearance: Float, val above: Boolean) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val inset = clearance.coerceAtMost(size.height)
        return Outline.Rectangle(
            Rect(
                0f,
                if (above) 0f else inset,
                size.width,
                if (above) size.height - inset else size.height,
            ),
        )
    }
}

@Composable
internal fun FloatingPaneDock(
    panes: List<BlockItem>,
    active: String?,
    programs: Map<String, ProgramIdentity>,
    titles: Map<String, String>,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
    directories: Map<String, String> = emptyMap(),
    hazeState: HazeState? = null,
    onExpandedChange: (Boolean) -> Unit = {},
    onDockBounds: (Rect) -> Unit = {},
    position: Offset = Offset(1f, 1f),
    onMove: (Offset) -> Unit = {},
    unread: Map<String, Int> = emptyMap(),
    topOcclusion: () -> Int = { 0 },
) {
    if (panes.isEmpty()) return
    var expanded by remember(panes.map { it.id }) { mutableStateOf(false) }
    val grouped = panes.size > 1
    val localHaze = remember { HazeState() }
    val haze = hazeState ?: localHaze
    val notifyBackdrop by rememberUpdatedState(onExpandedChange)
    val menu = updateTransition(grouped && expanded, label = "Floating pane menu")
    val showing = menu.currentState || menu.targetState || menu.isRunning
    LaunchedEffect(showing) { notifyBackdrop(showing) }
    DisposableEffect(Unit) { onDispose { notifyBackdrop(false) } }
    val layoutDirection = LocalLayoutDirection.current
    val dockIconWidth = 48.dp
    val countSize = 18.dp
    val dockWidth = if (grouped) dockIconWidth + countSize + 6.dp else dockIconWidth
    val dockHeight = 36.dp
    val hitHeight = 48.dp
    val dockTopInset = (hitHeight - dockHeight) / 2
    val dockClearance = dockHeight + 2.dp
    val rowHeight = 32.dp
    val rowGap = 6.dp
    val topInset = 12.dp
    val rowMaxWidth = 228.dp
    val fanReach = (12 + 3 * (panes.size - 2).coerceIn(0, 5)).dp
    val pendingCount = panes.sumOf { unread[it.id] ?: 0 }
    val scheme = MaterialTheme.colorScheme
    fun title(pane: BlockItem) =
        (programs[pane.id] ?: ProgramIdentity.Loading).paneTitle(
            pane.label,
            titles[pane.id].orEmpty(),
        )
    fun choose(pane: BlockItem) {
        if (pane.id == active) onMinimize() else onOpen(pane.id)
        expanded = false
    }
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
        val activityInset = (tabRailWidth(TabRailStyle.ICONS) - activityButtonDiameter) / 2
        val edge = activityInset.coerceAtMost((maxWidth - dockWidth).coerceAtLeast(0.dp) / 2)
        val travelX = (maxWidth - dockWidth - edge * 2).coerceAtLeast(0.dp)
        val bottomEdge =
            (paneIndicatorBottomInset - dockTopInset - 4.dp).coerceIn(
                0.dp,
                (maxHeight - hitHeight).coerceAtLeast(0.dp),
            )
        val topEdge = 6.dp.coerceAtMost((maxHeight - hitHeight - bottomEdge).coerceAtLeast(0.dp))
        val travelY = (maxHeight - hitHeight - topEdge - bottomEdge).coerceAtLeast(0.dp)
        val latestPosition by rememberUpdatedState(position)
        val savePosition by rememberUpdatedState(onMove)
        var dragged by remember { mutableStateOf<Offset?>(null) }
        fun physicalX(x: Float) = if (layoutDirection == LayoutDirection.Ltr) x else 1f - x
        fun Density.resting() =
            Offset(
                edge.toPx() + travelX.toPx() * physicalX(latestPosition.x),
                topEdge.toPx() + travelY.toPx() * latestPosition.y,
            )
        var menuPosition by remember { mutableStateOf(position) }
        val above = menuPosition.y >= .5f
        val left = physicalX(menuPosition.x) >= .5f
        val fanDirection = if (left) 1f else -1f
        val iconOnLeft = layoutDirection == LayoutDirection.Ltr
        val iconFromLeft = if (iconOnLeft) dockIconWidth / 2 else dockWidth - dockIconWidth / 2
        val dockIconInset = if (left) dockWidth - iconFromLeft else iconFromLeft
        menu.AnimatedVisibility(
            visible = { it },
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier =
                Modifier.layout { measurable, constraints ->
                    val at = dragged ?: resting()
                    val width = dockWidth.roundToPx()
                    val height = dockHeight.roundToPx()
                    val dockY = (at.y + dockTopInset.toPx()).roundToInt()
                    val availableWidth =
                        if (left) at.x.roundToInt() + width
                        else maxWidth.roundToPx() - at.x.roundToInt()
                    val availableHeight =
                        if (above) dockY + height else maxHeight.roundToPx() - dockY
                    val child =
                        measurable.measure(
                            constraints.copy(
                                minWidth = 0,
                                minHeight = 0,
                                maxWidth =
                                    minOf((rowMaxWidth + fanReach).roundToPx(), availableWidth)
                                        .coerceAtLeast(0),
                                maxHeight =
                                    minOf((256.dp + dockClearance).roundToPx(), availableHeight)
                                        .coerceAtLeast(0),
                            ),
                        )
                    layout(maxWidth.roundToPx(), maxHeight.roundToPx()) {
                        val x =
                            if (left) at.x.roundToInt() + width - child.width else at.x.roundToInt()
                        val y = if (above) dockY + height - child.height else dockY
                        child.place(x, y)
                    }
                },
        ) {
            val reveal =
                transition.animateFloat(
                    transitionSpec = {
                        tween(
                            if (targetState == EnterExitState.Visible) 300 else 220,
                            easing = LinearEasing,
                        )
                    },
                    label = "Floating pane reveal",
                ) {
                    if (it == EnterExitState.Visible) 1f else 0f
                }
            val scroll = rememberScrollState()
            // The animation can pass behind the dock. At rest the list scrolls above its button.
            Column(
                Modifier.heightIn(max = 256.dp + dockClearance)
                    .graphicsLayer {
                        shape = FloatingFanClip(dockClearance.toPx() * reveal.value, above)
                        clip = true
                    }
                    .verticalScroll(scroll, enabled = expanded, reverseScrolling = above)
                    .padding(
                        start = if (left == iconOnLeft) fanReach else 0.dp,
                        end = if (left == iconOnLeft) 0.dp else fanReach,
                        top = topInset + if (above) 0.dp else dockClearance,
                        bottom = topInset + if (above) dockClearance else 0.dp,
                    ),
                horizontalAlignment = if (left == iconOnLeft) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(rowGap),
            ) {
                panes.forEachIndexed { index, pane ->
                    val isActive = pane.id == active
                    val paneTitle = title(pane)
                    val rowDescription =
                        if (isActive)
                            stringResource(R.string.a11y_minimize_floating_pane_named, paneTitle)
                        else stringResource(R.string.a11y_open_floating_pane, paneTitle)
                    val rowUnread =
                        unread[pane.id]
                            ?.takeIf { it > 0 }
                            ?.let { stringResource(R.string.a11y_activity_new_items, it) }
                    Row(
                        Modifier.widthIn(max = rowMaxWidth)
                            .height(rowHeight)
                            .graphicsLayer {
                                val step = rowHeight.roundToPx() + rowGap.roundToPx()
                                val scrollFromTop =
                                    if (above && scroll.maxValue != Int.MAX_VALUE)
                                        scroll.maxValue - scroll.value
                                    else scroll.value
                                val rowTop =
                                    (topInset + if (above) 0.dp else dockClearance).roundToPx() +
                                        index * step - scrollFromTop
                                val dockY =
                                    if (above) scroll.viewportSize - dockHeight.toPx() / 2
                                    else dockHeight.toPx() / 2
                                val rise = dockY - rowTop - size.height / 2
                                val nearest = if (above) panes.lastIndex else 0
                                val firstCenter =
                                    (topInset + dockClearance).toPx() +
                                        (rowHeight - dockHeight).toPx() / 2
                                val distanceFromFirst =
                                    if (index == nearest) 0f
                                    else (kotlin.math.abs(rise) - firstCenter).coerceAtLeast(0f)
                                val visibleSteps =
                                    ((scroll.viewportSize -
                                            (dockClearance + topInset * 2 + rowHeight)
                                                .roundToPx()) / step)
                                        .coerceIn(1, panes.lastIndex.coerceAtLeast(1))
                                val distance =
                                    (distanceFromFirst / (step * visibleSteps)).coerceIn(0f, 1f)
                                val stagger =
                                    (distanceFromFirst / 208.dp.toPx()).coerceAtMost(1f) * .14f
                                val inViewport =
                                    if (above)
                                        rowTop + size.height > 0f &&
                                            rowTop < scroll.viewportSize - dockClearance.toPx()
                                    else
                                        rowTop + size.height > dockClearance.toPx() &&
                                            rowTop < scroll.viewportSize
                                val progress =
                                    if (inViewport)
                                        floatingFanEase.transform(
                                            ((reveal.value - stagger) / (1f - stagger)).coerceIn(
                                                0f,
                                                1f,
                                            ),
                                        )
                                    else 1f
                                val sweep = progress * (PI / 2)
                                // Bow inward in the middle, then return toward the dock's edge.
                                val arc =
                                    if (index == 0 || index == panes.lastIndex) 0f
                                    else 4f * distance * (1f - distance)
                                val inset = fanReach.toPx() * arc
                                val toDock = 1f - sin(sweep).toFloat()
                                val dockX = fanDirection * (size.width / 2 - dockIconInset.toPx())
                                transformOrigin = TransformOrigin.Center
                                translationX =
                                    dockX * toDock -
                                        fanDirection * inset * (1f - cos(sweep).toFloat())
                                translationY = rise * toDock
                                rotationZ =
                                    fanDirection *
                                        (if (above) 1f else -1f) *
                                        (6f + 10f * distance) *
                                        sin(progress * PI).toFloat()
                                val collapsedWidth =
                                    (dockHeight.toPx() / size.width.coerceAtLeast(1f)).coerceAtMost(
                                        1f,
                                    )
                                scaleX = collapsedWidth + (1f - collapsedWidth) * progress
                                scaleY = .66f + .34f * progress
                                alpha = (progress * 2.5f).coerceAtMost(1f)
                            }
                            .shadow(4.dp, RoundedCornerShape(16.dp), clip = false)
                            .paneFrost(haze, RoundedCornerShape(16.dp), active = isActive)
                            .clickable(enabled = enabled && expanded, role = Role.Button) {
                                choose(pane)
                            }
                            .semantics {
                                contentDescription = rowDescription
                                selected = isActive
                                if (rowUnread != null) stateDescription = rowUnread
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (left != iconOnLeft)
                            FloatingPaneMenuIcon(
                                programs[pane.id] ?: ProgramIdentity.Loading,
                                pane.id,
                                (unread[pane.id] ?: 0) > 0,
                                Modifier.padding(start = 8.dp),
                            )
                        Text(
                            listOf(paneTitle, directories[pane.id].orEmpty())
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            Modifier.weight(1f, fill = false)
                                .padding(start = 12.dp, end = 8.dp)
                                .graphicsLayer {
                                    alpha = ((reveal.value - .22f) / .40f).coerceIn(0f, 1f)
                                },
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            letterSpacing = 0.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color =
                                if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                        )
                        if (left == iconOnLeft)
                            FloatingPaneMenuIcon(
                                programs[pane.id] ?: ProgramIdentity.Loading,
                                pane.id,
                                (unread[pane.id] ?: 0) > 0,
                                Modifier.padding(end = 8.dp),
                            )
                    }
                }
            }
        }
        val capsuleShape = RoundedCornerShape(20.dp)
        val singleTitle = if (panes.size == 1) title(panes.single()) else ""
        val toggleDescription =
            when {
                grouped -> stringResource(R.string.a11y_floating_panes)
                active != null ->
                    stringResource(R.string.a11y_minimize_floating_pane_named, singleTitle)
                else -> stringResource(R.string.a11y_open_floating_pane, singleTitle)
            }
        val menuState =
            when {
                !grouped -> ""
                expanded -> stringResource(R.string.a11y_state_expanded)
                else -> stringResource(R.string.a11y_state_collapsed)
            }
        val activityState =
            if (pendingCount > 0) stringResource(R.string.a11y_activity_new_items, pendingCount)
            else ""
        val dockStateDescription =
            listOf(menuState, activityState).filter { it.isNotBlank() }.joinToString(", ")
        Box(
            Modifier.absoluteOffset {
                    val at = dragged ?: resting()
                    IntOffset(at.x.roundToInt(), at.y.roundToInt())
                }
                .size(dockWidth, hitHeight)
                .pointerInput(edge, topEdge, travelX, travelY, layoutDirection) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            expanded = false
                            dragged = resting()
                        },
                        onDragEnd = {
                            dragged?.let { at ->
                                val x =
                                    if (travelX > 0.dp)
                                        physicalX((at.x - edge.toPx()) / travelX.toPx())
                                    else latestPosition.x
                                val y =
                                    if (travelY > 0.dp) (at.y - topEdge.toPx()) / travelY.toPx()
                                    else latestPosition.y
                                savePosition(Offset(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
                            }
                            dragged = null
                        },
                        onDragCancel = { dragged = null },
                    ) { change, delta ->
                        change.consume()
                        val at = (dragged ?: resting()) + delta
                        dragged =
                            Offset(
                                at.x.coerceIn(edge.toPx(), (edge + travelX).toPx()),
                                at.y.coerceIn(topEdge.toPx(), (topEdge + travelY).toPx()),
                            )
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                ) {
                    if (grouped) {
                        if (!expanded) menuPosition = latestPosition
                        expanded = !expanded
                    } else choose(panes.single())
                }
                .semantics {
                    contentDescription = toggleDescription
                    selected = active != null
                    if (dockStateDescription.isNotBlank()) stateDescription = dockStateDescription
                },
            contentAlignment = Alignment.Center,
        ) {
            TransferNoticeSurface(
                Modifier.size(dockWidth, dockHeight)
                    .onPlaced { onDockBounds(it.boundsInWindow()) }
                    .shadow(4.dp, capsuleShape, clip = false)
                    .background(scheme.surface, capsuleShape)
                    .border(
                        if (active != null) 1.dp else .5.dp,
                        if (active != null) scheme.primary else scheme.outlineVariant,
                        capsuleShape,
                    )
                    .testTag("Floating dock capsule"),
                hazeState = haze,
                container = scheme.surface,
                title = null,
            ) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.width(dockIconWidth).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val index = panes.indexOfFirst { it.id == active }.coerceAtLeast(0)
                        Box {
                            ProgramStack(
                                panes.map { programs[it.id] ?: ProgramIdentity.Loading },
                                index,
                                fixedSlot = true,
                            )
                            if (pendingCount > 0)
                                FloatingPaneUnreadDot(
                                    Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 1.dp),
                                    "Floating dock unread dot",
                                )
                        }
                    }
                    if (grouped)
                        Box(
                            Modifier.size(countSize)
                                .background(scheme.surfaceVariant, CircleShape)
                                .clearAndSetSemantics {},
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                panes.size.toString(),
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun FloatingPaneMenuIcon(
    program: ProgramIdentity,
    id: String,
    unread: Boolean,
    modifier: Modifier,
) {
    Box(modifier.size(22.dp, 18.dp)) {
        PaneProgramTile(program, Modifier.fillMaxSize(), false, 0f)
        if (unread)
            FloatingPaneUnreadDot(
                Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
                "Floating pane unread dot $id",
            )
    }
}

@Composable
private fun FloatingPaneUnreadDot(modifier: Modifier, tag: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .size(8.dp)
            .border(1.5.dp, scheme.surface, CircleShape)
            .background(scheme.primary, CircleShape)
            .testTag(tag),
    )
}
