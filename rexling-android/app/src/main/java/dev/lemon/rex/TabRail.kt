package dev.lemon.rex

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState

internal enum class TabBarPosition(@androidx.annotation.StringRes val labelRes: Int) {
    BOTTOM(R.string.tab_position_bottom),
    LEFT(R.string.tab_position_left),
    RIGHT(R.string.tab_position_right),
}

internal enum class TabRailStyle(@androidx.annotation.StringRes val labelRes: Int) {
    ICONS(R.string.tab_rail_style_icons),
    TITLES(R.string.tab_rail_style_titles),
}

internal fun tabRailWidth(style: TabRailStyle) =
    when (style) {
        TabRailStyle.ICONS -> 52.dp
        TabRailStyle.TITLES -> 152.dp
    }

/**
 * Where the tab bar sits, and how a side rail draws its tabs. Kept separately for folded and
 * unfolded screens.
 */
internal data class TabBarLayout(
    val position: TabBarPosition = TabBarPosition.BOTTOM,
    val style: TabRailStyle = TabRailStyle.ICONS,
) {
    val side: Boolean
        get() = position != TabBarPosition.BOTTOM

    fun encode() = "${position.name},${style.name}"

    companion object {
        fun decode(raw: String?, default: TabBarLayout): TabBarLayout {
            val parts = raw?.split(',') ?: return default
            val position =
                TabBarPosition.entries.firstOrNull { it.name == parts.getOrNull(0) }
                    ?: return default
            val style =
                TabRailStyle.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: default.style
            return TabBarLayout(position, style)
        }
    }
}

/**
 * Which tab layout setting applies, by the app window's own width: the room the tabs actually have,
 * whether the window is a folded cover screen, the unfolded inner screen, or a split half of it. On
 * a Galaxy Z Fold: a portrait split half (~350dp) is narrow like a phone; the cover screen (~475dp)
 * and a landscape split half (~465dp) are medium; the inner screen held sideways (~933dp) is wide.
 */
internal enum class TabWidthClass(
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val rangeRes: Int,
    val key: String,
) {
    NARROW(R.string.tab_width_narrow, R.string.tab_width_narrow_range, "tab_layout_narrow"),
    MEDIUM(R.string.tab_width_medium, R.string.tab_width_medium_range, "tab_layout_medium"),
    WIDE(R.string.tab_width_wide, R.string.tab_width_wide_range, "tab_layout_wide");

    companion object {
        fun of(widthDp: Int): TabWidthClass =
            when {
                widthDp < 440 -> NARROW
                widthDp < 760 -> MEDIUM
                else -> WIDE
            }
    }
}

/**
 * The current app window's width class, and its width in dp; both follow folding, rotation and
 * split-screen resizes.
 */
@Composable
internal fun currentTabWidth(): Pair<TabWidthClass, Int> {
    val width = LocalConfiguration.current.screenWidthDp
    return TabWidthClass.of(width) to width
}

/**
 * A vertical tab list beside the terminal; [actions] (switcher, Activity, keyboard) sit at its
 * foot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TerminalTabRail(
    windows: List<WindowItem>,
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    panePrograms: (WindowItem) -> List<ProgramIdentity>,
    selectedPane: (WindowItem) -> Int,
    side: TabBarPosition,
    style: TabRailStyle,
    paneTitle: (WindowItem, Int) -> String = { _, _ -> "" },
    paneWorking: (WindowItem) -> Boolean = { false },
    onClose: (WindowItem) -> Unit = {},
    onMove: (String, String?) -> Boolean = { _, _ -> false },
    reorderKey: String = "",
    paneProgress: (WindowItem) -> TerminalProgress? = { null },
    tabAttention: (WindowItem) -> Int = { 0 },
    animateWorking: Boolean = true,
    onStyle: (TabRailStyle) -> Unit = {},
    extendUnderNav: Boolean = false,
    floating: Boolean = false,
    hazeState: HazeState? = null,
    handle: (@Composable () -> Unit)? = null,
    onFanToggle: ((String) -> Unit)? = null,
    onFanDismiss: () -> Unit = {},
    onFanAnchorBounds: (Rect) -> Unit = {},
    onFanBarBounds: (Rect) -> Unit = {},
    onFanAnchorGone: (String) -> Unit = {},
    onMenuOpenChange: (Boolean) -> Unit = {},
    actions: @Composable () -> Unit,
) {
    val titles = style == TabRailStyle.TITLES
    // Swipe across the rail toward the terminal to show titles, back toward the screen edge to hide
    // them.
    // Resizes are debounced, so the animated width sends the pane only its final size.
    val width by
        androidx.compose.animation.core.animateDpAsState(
            if (floating) (if (titles) 144.dp else 48.dp) else tabRailWidth(style),
            androidx.compose.animation.core.tween(180),
            label = "Tab rail width",
        )
    val outward = if (side == TabBarPosition.LEFT) 1f else -1f
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.toPx() }
    var swipe by remember { mutableFloatStateOf(0f) }
    // Gesture navigation treats edge swipes as Back. Android lets an app opt out for at most 200dp
    // of
    // each edge, so claim the rail's lowest 200dp (its buttons and nearest tabs); the toggle below
    // always works.
    val exclusion = with(androidx.compose.ui.platform.LocalDensity.current) { 200.dp.toPx() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val divider = scheme.outlineVariant.copy(alpha = .65f)
    val list = rememberLazyListState()
    var menuId by remember(reorderKey) { mutableStateOf<String?>(null) }
    val ids = windows.map { it.id }
    val index = ids.indexOf(selectedId)
    val currentOnMenuOpenChange by rememberUpdatedState(onMenuOpenChange)
    LaunchedEffect(ids) { menuId = null }
    LaunchedEffect(floating, menuId) {
        if (floating && menuId != null) onFanDismiss()
        currentOnMenuOpenChange(floating && menuId != null)
    }
    DisposableEffect(floating) { onDispose { if (floating) currentOnMenuOpenChange(false) } }
    LaunchedEffect(selectedId, reorderKey, index) {
        if (index >= 0) list.animateScrollToItem(index)
    }
    // A floating rail is a rounded card that hugs its tabs, so it has no fixed corner size.
    val pillShape = RexTokens.shape.tabCell
    val styleToggleDescription =
        stringResource(if (titles) R.string.show_icons_only else R.string.show_tab_titles)
    val tabsDescription = stringResource(R.string.a11y_tabs)
    BoxWithConstraints(
        Modifier.then(if (floating) Modifier else Modifier.fillMaxHeight())
            .width(width)
            // A floating rail is an overlay that hugs its tabs, so it never claims layout space.
            .then(if (floating) Modifier.wrapContentHeight() else Modifier)
            .then(
                if (floating) Modifier
                else
                    Modifier.background(scheme.surface).drawWithContent {
                        drawContent()
                        val stroke = .5.dp.toPx()
                        val x =
                            if (side == TabBarPosition.LEFT) size.width - stroke / 2 else stroke / 2
                        drawLine(
                            divider,
                            Offset(x, 0f),
                            Offset(x, size.height),
                            strokeWidth = stroke,
                        )
                    },
            )
            // Reaches the screen edge under the gesture handle; its buttons stay above it.
            .then(
                if (extendUnderNav && !floating)
                    Modifier.windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                    )
                else Modifier,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        // The toggle and the action buttons sit below the tabs. Measured so the pill can hug them;
        // seeded with their tallest arrangement so the first frame already fits.
        var chromePx by
            remember(density) { mutableIntStateOf(with(density) { 161.dp.roundToPx() }) }
        val listHeight =
            (windows.size * 48).dp + ((windows.size - 1).coerceAtLeast(0) * 4).dp + 4.dp
        val grip = if (handle != null) FloatingTabBarHandle else 0.dp
        val pillHeight =
            if (floating)
                (listHeight + with(density) { chromePx.toDp() } + grip + 8.dp).coerceAtMost(
                    maxHeight,
                )
            else maxHeight
        Column(
            Modifier.fillMaxWidth()
                .height(pillHeight)
                .then(
                    if (floating)
                        Modifier.shadow(3.dp, pillShape, clip = false)
                            .then(
                                if (hazeState == null)
                                    Modifier.clip(pillShape).background(scheme.surface)
                                else Modifier.paneFrost(hazeState, pillShape),
                            )
                    else Modifier,
                )
                .then(
                    if (floating)
                        Modifier.onGloballyPositioned { onFanBarBounds(it.boundsInRoot()) }
                            .testTag("Floating tab rail")
                    else Modifier,
                )
                .systemGestureExclusion { coordinates ->
                    val height = coordinates.size.height.toFloat()
                    androidx.compose.ui.geometry.Rect(
                        0f,
                        (height - exclusion).coerceAtLeast(0f),
                        coordinates.size.width.toFloat(),
                        height,
                    )
                }
                .draggable(
                    androidx.compose.foundation.gestures.rememberDraggableState { swipe += it },
                    androidx.compose.foundation.gestures.Orientation.Horizontal,
                    onDragStarted = { swipe = 0f },
                    onDragStopped = {
                        val next =
                            when {
                                swipe * outward > threshold -> TabRailStyle.TITLES
                                swipe * outward < -threshold -> TabRailStyle.ICONS
                                else -> style
                            }
                        swipe = 0f
                        if (next != style) {
                            haptic.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                            )
                            if (floating) onFanDismiss()
                            onStyle(next)
                        }
                    },
                )
                .semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(styleToggleDescription) {
                                onStyle(if (titles) TabRailStyle.ICONS else TabRailStyle.TITLES)
                                true
                            },
                        )
                }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A floating rail carries its grip in the same column, above the tabs.
            handle?.invoke()
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().semantics {
                    contentDescription = tabsDescription
                },
                state = list,
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
            ) {
                itemsIndexed(windows, key = { _, w -> w.id }) { i, w ->
                    fun moveBy(delta: Int): Boolean {
                        val target = i + delta
                        if (target !in windows.indices) return false
                        val reordered =
                            ids.toMutableList().apply {
                                removeAt(i)
                                add(target, w.id)
                            }
                        return onMove(w.id, reordered.getOrNull(target + 1))
                    }
                    val active = w.id == selectedId
                    val programs = panePrograms(w)
                    val pane = selectedPane(w)
                    val program = programs.getOrNull(pane) ?: ProgramIdentity.Loading
                    val title =
                        program.tabTitle(
                            w.label,
                            w.blocks.getOrNull(pane)?.label.orEmpty(),
                            i + 1,
                            paneTitle(w, pane),
                        )
                    val unread = tabAttention(w)
                    val progress = paneProgress(w)
                    val busy = paneWorking(w) || progress?.busy == true
                    val indicator = terminalProgressColor(progress)
                    val breathe =
                        animateWorking &&
                            if (progress != null) progress.busy && progress.percent == null
                            else active && busy
                    val workPhase = rememberWorkingPhase(breathe)
                    val tabDescription =
                        stringResource(
                            if (busy && progress == null) R.string.a11y_tab_working
                            else R.string.a11y_tab,
                            title,
                            program.label,
                        )
                    val unreadDescription =
                        pluralStringResource(R.plurals.a11y_unread_items, unread, unread)
                    val moveUpLabel = stringResource(R.string.move_up)
                    val moveDownLabel = stringResource(R.string.move_down)
                    val tabOptionsLabel = stringResource(R.string.tab_options)
                    Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                        if (floating && active) {
                            DisposableEffect(w.id) { onDispose { onFanAnchorGone(w.id) } }
                        }
                        val layoutDirection =
                            androidx.compose.ui.platform.LocalLayoutDirection.current
                        Row(
                            Modifier.fillMaxWidth()
                                .height(48.dp)
                                .then(
                                    if (floating && active)
                                        Modifier.onGloballyPositioned {
                                            onFanAnchorBounds(it.boundsInRoot())
                                        }
                                    else Modifier,
                                )
                                .background(
                                    if (active) scheme.surfaceVariant else Color.Transparent,
                                )
                                .drawWithContent {
                                    drawContent()
                                    if (active || busy || progress != null) {
                                        val bar = Size(3.dp.toPx(), 22.dp.toPx())
                                        val inset = 5.dp.toPx()
                                        val x =
                                            if (layoutDirection == LayoutDirection.Ltr) inset
                                            else size.width - inset - bar.width
                                        val origin = Offset(x, (size.height - bar.height) / 2)
                                        val brightness =
                                            if (breathe) workingBarBrightness(workPhase.value)
                                            else 1f
                                        val opacity =
                                            if (active || progress != null) brightness
                                            else .45f * brightness
                                        drawTabMarker(
                                            indicator,
                                            origin,
                                            bar,
                                            progress?.percent,
                                            opacity,
                                            vertical = true,
                                        )
                                    }
                                }
                                .semantics(mergeDescendants = true) {
                                    contentDescription =
                                        tabDescription +
                                            (progress?.let { ", ${it.description}" } ?: "")
                                }
                                .semantics {
                                    selected = active
                                    if (unread > 0) stateDescription = unreadDescription
                                    if (enabled)
                                        customActions = buildList {
                                            if (i > 0)
                                                add(
                                                    CustomAccessibilityAction(moveUpLabel) {
                                                        moveBy(-1)
                                                    },
                                                )
                                            if (i < windows.lastIndex)
                                                add(
                                                    CustomAccessibilityAction(moveDownLabel) {
                                                        moveBy(1)
                                                    },
                                                )
                                        }
                                }
                                .combinedClickable(
                                    enabled = enabled,
                                    role = Role.Tab,
                                    onLongClickLabel = tabOptionsLabel,
                                    onLongClick = {
                                        if (floating) onFanDismiss()
                                        menuId = w.id
                                    },
                                    onClick = {
                                        if (
                                            floating &&
                                                active &&
                                                w.blocks.size > 1 &&
                                                onFanToggle != null
                                        ) {
                                            onFanToggle(w.id)
                                        } else {
                                            if (floating) onFanDismiss()
                                            onSelect(w.id)
                                        }
                                    },
                                )
                                .padding(
                                    start = if (titles) 14.dp else 5.dp,
                                    end = if (titles) 10.dp else 0.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement =
                                if (titles) Arrangement.spacedBy(4.dp) else Arrangement.Center,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                ProgramStack(programs, pane, fixedSlot = true)
                                if (unread > 0)
                                    Box(
                                        Modifier.align(Alignment.TopEnd)
                                            .offset(x = 2.dp, y = (-2).dp)
                                            .size(8.dp)
                                            .border(1.5.dp, scheme.background, CircleShape)
                                            .background(scheme.primary, CircleShape)
                                            .semantics { testTag = "Tab unread dot ${w.id}" },
                                    )
                            }
                            if (titles)
                                Text(
                                    title,
                                    Modifier.weight(1f),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color =
                                        if (active) scheme.onSurface else scheme.onSurfaceVariant,
                                )
                        }
                        DropdownMenu(
                            menuId == w.id,
                            { menuId = null },
                            containerColor = scheme.surface,
                            tonalElevation = 0.dp,
                        ) {
                            TerminalMenuItem(
                                moveUpLabel,
                                ChromeIcon.UP,
                                enabled = enabled && i > 0,
                                onClick = {
                                    menuId = null
                                    moveBy(-1)
                                },
                            )
                            TerminalMenuItem(
                                moveDownLabel,
                                ChromeIcon.DOWN,
                                enabled = enabled && i < windows.lastIndex,
                                onClick = {
                                    menuId = null
                                    moveBy(1)
                                },
                            )
                            TerminalMenuItem(
                                stringResource(R.string.close_tab),
                                ChromeIcon.CLOSE,
                                enabled = enabled,
                                onClick = {
                                    menuId = null
                                    onClose(w)
                                },
                            )
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().onSizeChanged { chromePx = it.height },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HorizontalDivider(
                    Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    thickness = .5.dp,
                    color = divider,
                )
                // Points the way the rail will go: toward the terminal to show titles, toward the
                // edge to hide them.
                val towardTerminal = if (side == TabBarPosition.LEFT) -90f else 90f
                ChromeButton(
                    ChromeIcon.CHEVRON,
                    styleToggleDescription,
                    modifier =
                        Modifier.graphicsLayer {
                            rotationZ = if (titles) -towardTerminal else towardTerminal
                        },
                ) {
                    onStyle(if (titles) TabRailStyle.ICONS else TabRailStyle.TITLES)
                }
                Box(Modifier.padding(top = 4.dp)) { actions() }
            }
        }
    }
}
