package dev.lemon.rex

import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val controlShortcuts = ControlShortcutConfig.defaults.map { it.keys }

internal enum class ControlShortcutGesture {
    HOLD,
    DOUBLE_TAP,
}

private const val CONTROL_SHORTCUT_COLUMNS = 3
private const val CONTROL_SHORTCUT_PAGE_SIZE = 12

@Composable
internal fun ControlDockKey(
    enabled: Boolean,
    active: Boolean,
    readable: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onShortcut: (String) -> Unit,
    gesture: ControlShortcutGesture = ControlShortcutGesture.HOLD,
    shortcuts: List<ControlShortcut> = ControlShortcutConfig.defaults,
    inputKey: String = "",
) {
    val pages =
        ((shortcuts.size + CONTROL_SHORTCUT_PAGE_SIZE - 1) / CONTROL_SHORTCUT_PAGE_SIZE)
            .coerceAtLeast(1)
    val pager = rememberPagerState(pageCount = { pages })
    val rows =
        ((shortcuts.size.coerceAtMost(CONTROL_SHORTCUT_PAGE_SIZE) + CONTROL_SHORTCUT_COLUMNS - 1) /
                CONTROL_SHORTCUT_COLUMNS)
            .coerceAtLeast(1)
    val footer = if (pages > 1) 32.dp else 0.dp
    var visible by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var highlighted by remember { mutableIntStateOf(-1) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var screenAnchor by remember { mutableStateOf(Rect.Zero) }
    var popup by remember { mutableStateOf(IntRect.Zero) }
    val scope = rememberCoroutineScope()
    val toggle by rememberUpdatedState(onToggle)
    val send by rememberUpdatedState(onShortcut)
    val latched by rememberUpdatedState(active)
    val density = LocalDensity.current
    val padding = with(density) { 8.dp.toPx() }
    val gap = with(density) { 4.dp.toPx() }
    val configuration = LocalViewConfiguration.current
    val controlLabel = stringResource(R.string.a11y_control)
    val controlShortcutsLabel = stringResource(R.string.a11y_control_shortcuts)
    val haptics = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    fun dismiss() {
        visible = false
        highlighted = -1
    }
    fun closeFromTrigger() {
        dismiss()
        if (latched) toggle()
    }
    LaunchedEffect(enabled, gesture, shortcuts, inputKey) {
        dismiss()
        pressed = false
        pager.scrollToPage(0)
    }
    fun choose(index: Int) {
        if (enabled) shortcuts.getOrNull(index)?.let { send(it.keys) }
        dismiss()
    }
    fun hit(x: Float, y: Float): Int {
        val px = x - popup.left - padding
        val py = y - popup.top - padding
        val w =
            (popup.width - 2 * padding - (CONTROL_SHORTCUT_COLUMNS - 1) * gap) /
                CONTROL_SHORTCUT_COLUMNS
        val h =
            (popup.height - with(density) { footer.toPx() } - 2 * padding - (rows - 1) * gap) / rows
        if (w <= 0 || h <= 0 || px < 0 || py < 0) return -1
        val col = (px / (w + gap)).toInt()
        val row = (py / (h + gap)).toInt()
        val index =
            pager.currentPage * CONTROL_SHORTCUT_PAGE_SIZE + row * CONTROL_SHORTCUT_COLUMNS + col
        return if (
            col in 0 until CONTROL_SHORTCUT_COLUMNS &&
                row in 0 until rows &&
                index in shortcuts.indices &&
                px - col * (w + gap) < w &&
                py - row * (h + gap) < h
        )
            index
        else -1
    }
    BackHandler(visible) { dismiss() }
    val provider = rememberSpeechBubblePositioner { popup = it }
    val selected = active || pressed || visible
    Box(
        modifier
            .fillMaxHeight()
            .onGloballyPositioned {
                anchor = it.boundsInWindow()
                screenAnchor =
                    Rect(
                        it.localToScreen(Offset.Zero),
                        androidx.compose.ui.geometry.Size(
                            it.size.width.toFloat(),
                            it.size.height.toFloat(),
                        ),
                    )
            }
            .clip(RoundedCornerShape(11.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .semantics {
                contentDescription = controlLabel
                this.selected = active
                role = Role.Button
                if (enabled) {
                    onClick {
                        if (visible) closeFromTrigger() else toggle()
                        true
                    }
                    onLongClick(label = controlShortcutsLabel) {
                        visible = true
                        true
                    }
                } else disabled()
            }
            .pointerInput(enabled, gesture, shortcuts, inputKey) {
                if (!enabled) return@pointerInput
                var lastTap: Long? = null
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val closingPopup = visible
                    // Consume only when closing the open shortcut popup so parent horizontal
                    // toolbars can still intercept horizontal scroll drags from an idle Ctrl key.
                    if (closingPopup) down.consume()
                    pressed = true
                    // Trigger taps bypass outside dismissal, so the release can close and clear
                    // Ctrl.
                    val doubleTap =
                        gesture == ControlShortcutGesture.DOUBLE_TAP &&
                            lastTap?.let {
                                down.uptimeMillis - it in
                                    configuration.doubleTapMinTimeMillis..configuration
                                            .doubleTapTimeoutMillis
                            } == true
                    lastTap = null
                    var opened = false
                    var released = false
                    var moved = false
                    val timer =
                        if (!closingPopup && gesture == ControlShortcutGesture.HOLD)
                            scope.launch {
                                delay(configuration.longPressTimeoutMillis)
                                opened = true
                                visible = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        else null
                    try {
                        while (true) {
                            val change =
                                awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    ?: break
                            if (change.isConsumed) break
                            if (
                                abs(change.position.x - down.position.x) >
                                    configuration.touchSlop ||
                                    abs(change.position.y - down.position.y) >
                                        configuration.touchSlop
                            ) {
                                moved = true
                                if (!opened) timer?.cancel()
                            }
                            if (opened || closingPopup || (!change.pressed && !moved)) {
                                change.consume()
                            }
                            if (opened && visible)
                                highlighted =
                                    hit(
                                        change.position.x + anchor.left,
                                        change.position.y + anchor.top,
                                    )
                            if (!change.pressed) {
                                released = true
                                timer?.cancel()
                                if (closingPopup) {
                                    closeFromTrigger()
                                    lastTap = null
                                } else if (opened) {
                                    if (visible && highlighted >= 0) choose(highlighted)
                                } else if (
                                    !moved &&
                                        change.position.x in 0f..size.width.toFloat() &&
                                        change.position.y in 0f..size.height.toFloat()
                                ) {
                                    // Each tap toggles immediately; the second restores the first
                                    // toggle.
                                    toggle()
                                    if (
                                        doubleTap &&
                                            change.uptimeMillis - down.uptimeMillis <
                                                configuration.longPressTimeoutMillis
                                    ) {
                                        visible = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else {
                                        if (
                                            gesture == ControlShortcutGesture.DOUBLE_TAP &&
                                                change.uptimeMillis - down.uptimeMillis <
                                                    configuration.longPressTimeoutMillis
                                        )
                                            lastTap = change.uptimeMillis
                                    }
                                }
                                break
                            }
                        }
                    } finally {
                        timer?.cancel()
                        pressed = false
                        if (!released || opened) dismiss()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        ChromeSymbol(
            ChromeIcon.CONTROL,
            Modifier.size(20.dp),
            (if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface)
                .copy(alpha = if (readable) 1f else .38f),
        ) // alpha-ok disabled glyph opacity
        if (visible)
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { dismiss() },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
            ) {
                val popupView = LocalView.current.rootView
                val triggerBounds by rememberUpdatedState(screenAnchor)
                DisposableEffect(popupView) {
                    // ACTION_OUTSIDE arrives before the underlying trigger's DOWN. Keep this
                    // popup open only for that trigger; its pointer handler owns the close.
                    popupView.setOnTouchListener { _, event ->
                        event.actionMasked == MotionEvent.ACTION_OUTSIDE &&
                            triggerBounds.contains(Offset(event.rawX, event.rawY))
                    }
                    onDispose { popupView.setOnTouchListener(null) }
                }
                SpeechBubble(
                    provider,
                    Modifier.width(244.dp).semantics { contentDescription = controlShortcutsLabel },
                    shadowElevation = 0.dp,
                ) {
                    Column(
                        Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        VerticalPager(
                            pager,
                            Modifier.height((rows * 44 + (rows - 1) * 4).dp).fillMaxWidth(),
                            userScrollEnabled = pages > 1,
                        ) { page ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                repeat(rows) { row ->
                                    Row(
                                        Modifier.fillMaxWidth().height(44.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        repeat(CONTROL_SHORTCUT_COLUMNS) { col ->
                                            val index =
                                                page * CONTROL_SHORTCUT_PAGE_SIZE +
                                                    row * CONTROL_SHORTCUT_COLUMNS +
                                                    col
                                            val item = shortcuts.getOrNull(index)
                                            val current = highlighted == index
                                            if (item == null)
                                                Spacer(Modifier.weight(1f).fillMaxHeight())
                                            else
                                                Box(
                                                    Modifier.weight(1f)
                                                        .fillMaxHeight()
                                                        .clip(RoundedCornerShape(11.dp))
                                                        .background(
                                                            if (current)
                                                                MaterialTheme.colorScheme.primary
                                                            else
                                                                androidx.compose.ui.graphics.Color
                                                                    .Transparent,
                                                        )
                                                        .semantics {
                                                            contentDescription =
                                                                if (
                                                                    item.keys.length == 1 &&
                                                                        item.label ==
                                                                            "^" +
                                                                                item.keys.uppercase(
                                                                                    java.util.Locale
                                                                                        .ROOT,
                                                                                )
                                                                )
                                                                    context.getString(
                                                                        R.string.a11y_control_key,
                                                                        item.keys.uppercase(
                                                                            java.util.Locale.ROOT,
                                                                        ),
                                                                    )
                                                                else
                                                                    context.getString(
                                                                        R.string
                                                                            .a11y_control_shortcut,
                                                                        item.label,
                                                                    )
                                                            this.selected = current
                                                        }
                                                        .clickable(
                                                            enabled = enabled,
                                                            role = Role.Button,
                                                        ) {
                                                            choose(index)
                                                        },
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        item.label,
                                                        Modifier.padding(horizontal = 3.dp),
                                                        fontFamily = MonoUi,
                                                        fontSize =
                                                            if (item.label.length > 5) 11.sp
                                                            else 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 2,
                                                        overflow =
                                                            androidx.compose.ui.text.style
                                                                .TextOverflow
                                                                .Ellipsis,
                                                        color =
                                                            if (current)
                                                                MaterialTheme.colorScheme.onPrimary
                                                            else
                                                                MaterialTheme.colorScheme.onSurface,
                                                    )
                                                }
                                        }
                                    }
                                }
                            }
                        }
                        if (pages > 1)
                            Row(
                                Modifier.fillMaxWidth().height(28.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                val prevPageDesc = stringResource(R.string.a11y_prev_ctrl_page)
                                val nextPageDesc = stringResource(R.string.a11y_next_ctrl_page)
                                TextButton(
                                    onClick = {
                                        highlighted = -1
                                        scope.launch {
                                            pager.animateScrollToPage(pager.currentPage - 1)
                                        }
                                    },
                                    enabled = pager.currentPage > 0,
                                    contentPadding = PaddingValues(0.dp),
                                    modifier =
                                        Modifier.height(28.dp).semantics {
                                            contentDescription = prevPageDesc
                                        },
                                ) {
                                    Text("↑", fontSize = 13.sp)
                                }
                                Text(
                                    "${pager.currentPage+1}/$pages",
                                    fontFamily = MonoUi,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = {
                                        highlighted = -1
                                        scope.launch {
                                            pager.animateScrollToPage(pager.currentPage + 1)
                                        }
                                    },
                                    enabled = pager.currentPage < pages - 1,
                                    contentPadding = PaddingValues(0.dp),
                                    modifier =
                                        Modifier.height(28.dp).semantics {
                                            contentDescription = nextPageDesc
                                        },
                                ) {
                                    Text("↓", fontSize = 13.sp)
                                }
                            }
                    }
                }
            }
    }
}
