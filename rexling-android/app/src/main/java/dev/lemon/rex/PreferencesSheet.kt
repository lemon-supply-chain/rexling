package dev.lemon.rex

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreferencesSheet(
    onAppearance: () -> Unit,
    onFont: () -> Unit,
    onAlerts: () -> Unit,
    onAbout: () -> Unit,
    onClose: () -> Unit,
    shortcutGesture: ControlShortcutGesture = ControlShortcutGesture.HOLD,
    onShortcutGesture: (ControlShortcutGesture) -> Unit = {},
    onNeovim: () -> Unit = {},
    arrowJoystickEnabled: Boolean = true,
    onArrowJoystick: (Boolean) -> Unit = {},
    enterSubmits: Boolean = false,
    onEnterSubmits: (Boolean) -> Unit = {},
    toolbarWithKeyboardOnly: Boolean = false,
    onToolbarWithKeyboardOnly: (Boolean) -> Unit = {},
    toolbarSystemColors: Boolean = false,
    onToolbarSystemColors: (Boolean) -> Unit = {},
    paneLayouts: Map<PaneOrientation, Boolean> = emptyMap(),
    paneOrientation: PaneOrientation = currentPaneOrientation(),
    onPaneLayout: (PaneOrientation, Boolean) -> Unit = { _, _ -> },
    tabLayouts: Map<TabWidthClass, TabBarLayout> = emptyMap(),
    tabWidth: Pair<TabWidthClass, Int> = TabWidthClass.NARROW to 0,
    onTabLayout: (TabWidthClass, TabBarLayout) -> Unit = { _, _ -> },
    floatingTabs: Boolean = false,
    onFloatingTabs: (Boolean) -> Unit = {},
    compactTopBar: Boolean = false,
    onCompactTopBar: (Boolean) -> Unit = {},
    compactHeaderMode: CompactHeaderMode = CompactHeaderMode.NEVER,
    onCompactHeaderMode: (CompactHeaderMode) -> Unit = {},
    immersiveStatusBar: Boolean = false,
    onImmersiveStatusBar: (Boolean) -> Unit = {},
    codexTitleAttention: Boolean = false,
    onCodexTitleAttention: (Boolean) -> Unit = {},
    frostedEffectsEnabled: Boolean = true,
    onFrostedEffects: (Boolean) -> Unit = {},
    minimizeFloatingPanes: Boolean = false,
    onMinimizeFloatingPanes: (Boolean) -> Unit = {},
    onNetwork: () -> Unit = {},
    keepScreenAwake: KeepScreenAwake = KeepScreenAwake.SYSTEM,
    onKeepScreenAwake: (KeepScreenAwake) -> Unit = {},
    onToolbar: () -> Unit = {},
    onControlItems: () -> Unit = {},
    onSshKeys: () -> Unit = {},
    keyboardSuggestionsEnabled: Boolean = false,
    onKeyboardSuggestions: (Boolean) -> Unit = {},
    language: String = "system",
    onLanguage: () -> Unit = {},
    terminalPadding: TerminalPadding = TerminalPadding.NORMAL,
    onTerminalPadding: (TerminalPadding) -> Unit = {},
    extendPadding: Boolean = true,
    onExtendPadding: (Boolean) -> Unit = {},
    predictiveEcho: PredictiveEcho = PredictiveEcho.SLOW,
    onPredictiveEcho: (PredictiveEcho) -> Unit = {},
    hazeState: dev.chrisbanes.haze.HazeState? = null,
) {
    // Built outside the sheet on purpose: the frost redraws when its host window draws, and inside
    // the
    // sheet that host is the sheet's own window, which then redraws itself every frame while open.
    val frost = Modifier.drawerFrost(hazeState, BottomSheetDefaults.ExpandedShape)
    // Read only from pointer callbacks, so this stays out of composition state.
    val gesture = remember { SheetGesture() }
    var isPullingHandle by remember { mutableStateOf(false) }
    DrawerSheet(onClose, dismissOnBack = true) { dismiss ->
        val pull by rememberUpdatedState(LocalDrawerPull.current)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val contentMaxHeight = (maxHeight - 64.dp).coerceAtLeast(200.dp)
            Column(
                frost
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .pointerInput(Unit) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down =
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                            val inHandle = down.position.y <= gesture.handleZoneBottomPx
                            val pointerId = down.id
                            val startX = down.position.x
                            val startY = down.position.y
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                    if (inHandle && change != null && change.pressed) {
                                        val dx = change.position.x - startX
                                        val dy = change.position.y - startY
                                        isPullingHandle = dy > touchSlop && dy > kotlin.math.abs(dx)
                                    }
                                    if (event.changes.none { it.pressed }) break
                                }
                            } finally {
                                isPullingHandle = false
                            }
                        }
                    },
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .testTag("Preferences handle zone")
                        .onGloballyPositioned {
                            gesture.handleZoneBottomPx = it.size.height.toFloat()
                        },
                ) {
                    DrawerHandle(
                        dismiss,
                        active = isPullingHandle,
                        modifier = Modifier.testTag("Preferences drawer handle"),
                    )
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(start = 16.dp, end = 4.dp)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragStart = { pull?.start?.invoke() },
                                    onDragEnd = { pull?.finish?.invoke(true) },
                                    onDragCancel = { pull?.finish?.invoke(false) },
                                    onVerticalDrag = { change, amount ->
                                        change.consume()
                                        pull?.move?.invoke(amount)
                                    },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.preferences),
                            Modifier.weight(1f),
                            style = SettingsType.row,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        ChromeButton(
                            ChromeIcon.CLOSE,
                            stringResource(R.string.close_preferences),
                            onClick = dismiss,
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().testTag("Preferences body area")) {
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = contentMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(top = 8.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsSection(
                            stringResource(R.string.section_display),
                            compact = true,
                        ) {
                            SettingsRow(
                                stringResource(R.string.appearance),
                                stringResource(R.string.appearance_detail),
                                SettingsGlyph.APPEARANCE,
                                onAppearance,
                            )
                            SettingsDivider()
                            SettingsRow(
                                stringResource(R.string.font_size),
                                stringResource(R.string.font_size_detail),
                                SettingsGlyph.FONT,
                                onFont,
                            )
                            SettingsDivider()
                            Column(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    stringResource(R.string.compact_header_mode),
                                    style = SettingsType.paneTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                PreferenceSegments(
                                    CompactHeaderMode.entries,
                                    compactHeaderMode,
                                    { stringResource(it.labelRes) },
                                    showCheck = true,
                                    onSelect = onCompactHeaderMode,
                                )
                            }
                            SettingsDivider()
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        immersiveStatusBar,
                                        role = androidx.compose.ui.semantics.Role.Switch,
                                        onValueChange = onImmersiveStatusBar,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.immersive_mode),
                                        style = SettingsType.row,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        stringResource(R.string.immersive_mode_detail),
                                        style = SettingsType.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = immersiveStatusBar, onCheckedChange = null)
                            }
                            SettingsDivider()
                            SettingsRow(
                                stringResource(R.string.language),
                                when (language) {
                                    "zh" -> stringResource(R.string.language_zh)
                                    "en" -> stringResource(R.string.language_en)
                                    else -> stringResource(R.string.language_system)
                                },
                                SettingsGlyph.LANGUAGE,
                                onLanguage,
                            )
                            SettingsDivider()
                            Column(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    stringResource(R.string.terminal_padding),
                                    style = SettingsType.row,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    stringResource(R.string.terminal_padding_detail),
                                    style = SettingsType.detail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TerminalPadding.entries.forEach { option ->
                                        val labelRes =
                                            when (option) {
                                                TerminalPadding.NONE ->
                                                    R.string.terminal_padding_none
                                                TerminalPadding.SMALL ->
                                                    R.string.terminal_padding_small
                                                TerminalPadding.NORMAL ->
                                                    R.string.terminal_padding_normal
                                            }
                                        FilterChip(
                                            selected = terminalPadding == option,
                                            onClick = { onTerminalPadding(option) },
                                            label = {
                                                Text(
                                                    stringResource(labelRes),
                                                    style = SettingsType.row,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            SettingsDivider()
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        extendPadding,
                                        role = androidx.compose.ui.semantics.Role.Switch,
                                        onValueChange = onExtendPadding,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.extend_padding),
                                        style = SettingsType.row,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        stringResource(
                                            if (extendPadding) R.string.extend_padding_detail_on
                                            else R.string.extend_padding_detail_off,
                                        ),
                                        style = SettingsType.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = extendPadding, onCheckedChange = null)
                            }
                            SettingsDivider()
                            Column(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    stringResource(R.string.keep_screen_awake),
                                    style = SettingsType.row,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    stringResource(R.string.keep_screen_awake_desc),
                                    style = SettingsType.detail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                KeepScreenAwake.entries.forEach { mode ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .selectable(
                                                mode == keepScreenAwake,
                                                role =
                                                    androidx.compose.ui.semantics.Role.RadioButton,
                                                onClick = { onKeepScreenAwake(mode) },
                                            )
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = mode == keepScreenAwake,
                                            onClick = null,
                                        )
                                        Column(Modifier.padding(start = 8.dp)) {
                                            Text(
                                                stringResource(mode.labelRes),
                                                style = SettingsType.row,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                stringResource(mode.detailRes),
                                                style = SettingsType.detail,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        TabLayoutPreferences(
                            tabLayouts,
                            tabWidth,
                            onTabLayout,
                            floatingTabs,
                            onFloatingTabs,
                        )
                        PaneLayoutPreferences(
                            paneLayouts,
                            paneOrientation,
                            onPaneLayout,
                            minimizeFloatingPanes,
                            onMinimizeFloatingPanes,
                        )
                        SettingsSection(
                            stringResource(R.string.section_keyboard),
                            compact = true,
                        ) {
                            SettingsRow(
                                stringResource(R.string.keyboard_toolbar),
                                stringResource(R.string.keyboard_toolbar_detail),
                                SettingsGlyph.COMMAND,
                                onToolbar,
                            )
                            SettingsDivider()
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        toolbarWithKeyboardOnly,
                                        role = androidx.compose.ui.semantics.Role.Switch,
                                        onValueChange = onToolbarWithKeyboardOnly,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.show_toolbar_only_with_keyboard,
                                        ),
                                        style = SettingsType.row,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.show_toolbar_only_with_keyboard_desc,
                                        ),
                                        style = SettingsType.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = toolbarWithKeyboardOnly,
                                    onCheckedChange = null,
                                )
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                SettingsDivider()
                                Row(
                                    Modifier.fillMaxWidth()
                                        .toggleable(
                                            toolbarSystemColors,
                                            role = androidx.compose.ui.semantics.Role.Switch,
                                            onValueChange = onToolbarSystemColors,
                                        )
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(
                                        Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.toolbar_system_colors),
                                            style = SettingsType.row,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            stringResource(R.string.toolbar_system_colors_detail),
                                            style = SettingsType.detail,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = toolbarSystemColors,
                                        onCheckedChange = null,
                                    )
                                }
                            }
                            SettingsDivider()
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        keyboardSuggestionsEnabled,
                                        role = androidx.compose.ui.semantics.Role.Switch,
                                        onValueChange = onKeyboardSuggestions,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    stringResource(R.string.keyboard_suggestions),
                                    Modifier.weight(1f),
                                    style = SettingsType.row,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Switch(
                                    checked = keyboardSuggestionsEnabled,
                                    onCheckedChange = null,
                                )
                            }
                            SettingsDivider()
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        arrowJoystickEnabled,
                                        role = androidx.compose.ui.semantics.Role.Switch,
                                        onValueChange = onArrowJoystick,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.use_joystick),
                                        style = SettingsType.row,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        stringResource(
                                            if (arrowJoystickEnabled)
                                                R.string.use_joystick_detail_on
                                            else R.string.use_joystick_detail_off,
                                        ),
                                        style = SettingsType.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = arrowJoystickEnabled, onCheckedChange = null)
                            }
                            SettingsDivider()
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        enterSubmits,
                                        role = androidx.compose.ui.semantics.Role.Switch,
                                        onValueChange = onEnterSubmits,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.keyboard_enter_sends),
                                        style = SettingsType.row,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        stringResource(
                                            if (enterSubmits)
                                                R.string.keyboard_enter_sends_detail_on
                                            else R.string.keyboard_enter_sends_detail_off,
                                        ),
                                        style = SettingsType.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = enterSubmits, onCheckedChange = null)
                            }
                            SettingsDivider()
                            Column(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    stringResource(R.string.ctrl_shortcuts),
                                    style = SettingsType.paneTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    stringResource(R.string.ctrl_shortcuts_detail),
                                    style = SettingsType.detail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                PreferenceSegments(
                                    ControlShortcutGesture.entries,
                                    shortcutGesture,
                                    {
                                        if (it == ControlShortcutGesture.HOLD)
                                            stringResource(R.string.ctrl_gesture_hold)
                                        else stringResource(R.string.ctrl_gesture_double_tap)
                                    },
                                    showCheck = true,
                                    onSelect = onShortcutGesture,
                                )
                            }
                            SettingsDivider()
                            SettingsRow(
                                stringResource(R.string.ctrl_shortcut_buttons),
                                stringResource(R.string.ctrl_shortcut_buttons_detail),
                                SettingsGlyph.COMMAND,
                                onControlItems,
                            )
                            SettingsDivider()
                            SettingsRow(
                                stringResource(R.string.neovim_shortcuts),
                                stringResource(R.string.neovim_shortcuts_detail),
                                SettingsGlyph.COMMAND,
                                onNeovim,
                            )
                        }
                        SettingsSection(
                            stringResource(R.string.section_connections),
                            compact = true,
                        ) {
                            SettingsRow(
                                stringResource(R.string.network),
                                stringResource(R.string.network_detail),
                                SettingsGlyph.NETWORK,
                                onNetwork,
                            )
                            SettingsDivider()
                            SettingsRow(
                                stringResource(R.string.ssh_keys),
                                stringResource(R.string.ssh_keys_detail),
                                SettingsGlyph.KEY,
                                onSshKeys,
                            )
                            SettingsDivider()
                            SettingsRow(
                                stringResource(R.string.background_alerts),
                                stringResource(R.string.background_alerts_detail),
                                SettingsGlyph.ALERTS,
                                onAlerts,
                            )
                        }
                        SettingsSection(
                            stringResource(R.string.section_port_forwarding),
                            compact = true,
                        ) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    stringResource(R.string.port_conflict_title),
                                    style = SettingsType.row,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    stringResource(R.string.port_conflict_desc),
                                    style = SettingsType.detail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                PortConflictPolicy.entries.forEach { choice ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .selectable(
                                                PortForwards.policy == choice,
                                                role =
                                                    androidx.compose.ui.semantics.Role.RadioButton,
                                                onClick = { PortForwards.changePolicy(choice) },
                                            )
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = PortForwards.policy == choice,
                                            onClick = null,
                                        )
                                        Text(
                                            stringResource(choice.labelRes),
                                            Modifier.padding(start = 8.dp),
                                            style = SettingsType.row,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                        SettingsSection(
                            stringResource(R.string.section_experimental),
                            compact = true,
                        ) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        value = codexTitleAttention,
                                        role = androidx.compose.ui.semantics.Role.Switch,
                                        onValueChange = onCodexTitleAttention,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.codex_title_attention),
                                        style = SettingsType.row,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        stringResource(R.string.codex_title_attention_detail),
                                        style = SettingsType.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = codexTitleAttention, onCheckedChange = null)
                            }
                            SettingsDivider()
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        value = compactTopBar,
                                        role = androidx.compose.ui.semantics.Role.Switch,
                                        onValueChange = onCompactTopBar,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.compact_top_bar),
                                        style = SettingsType.row,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        stringResource(R.string.compact_top_bar_detail),
                                        style = SettingsType.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = compactTopBar, onCheckedChange = null)
                            }
                            SettingsDivider()
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        value = frostedEffectsEnabled,
                                        role = androidx.compose.ui.semantics.Role.Switch,
                                        onValueChange = onFrostedEffects,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.frosted_effects),
                                        style = SettingsType.row,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        stringResource(R.string.frosted_effects_detail),
                                        style = SettingsType.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = frostedEffectsEnabled, onCheckedChange = null)
                            }
                            SettingsDivider()
                            Column(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    stringResource(R.string.predictive_echo),
                                    style = SettingsType.row,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    stringResource(R.string.predictive_echo_summary),
                                    style = SettingsType.detail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PredictiveEcho.entries.forEach { mode ->
                                        FilterChip(
                                            selected = predictiveEcho == mode,
                                            onClick = { onPredictiveEcho(mode) },
                                            label = {
                                                Text(
                                                    stringResource(mode.labelRes),
                                                    style = SettingsType.row,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        SettingsSection(stringResource(R.string.section_app), compact = true) {
                            SettingsRow(
                                stringResource(R.string.about_rexling),
                                stringResource(R.string.about_rexling_detail),
                                SettingsGlyph.INFO,
                                onAbout,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LanguageSheet(
    language: String,
    onLanguage: (String) -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    onClose: () -> Unit,
) {
    SettingsDrawer(
        stringResource(R.string.language),
        stringResource(R.string.close_language),
        onClose,
        hazeState,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(stringResource(R.string.language), compact = true) {
                val options =
                    listOf(
                        Triple("system", R.string.language_system, R.string.language_system_detail),
                        Triple("zh", R.string.language_zh, R.string.language_zh_native),
                        Triple("en", R.string.language_en, R.string.language_en_native),
                    )
                options.forEachIndexed { index, (key, labelRes, detailRes) ->
                    if (index > 0) SettingsDivider()
                    val active = language == key
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = active,
                                role = androidx.compose.ui.semantics.Role.RadioButton,
                                onClick = { onLanguage(key) },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = active, onClick = null)
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                stringResource(labelRes),
                                style = SettingsType.row,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                stringResource(detailRes),
                                style = SettingsType.detail,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (active) {
                            ChromeSymbol(
                                ChromeIcon.ENTER,
                                Modifier.size(16.dp),
                                MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LanguageDialog(
    language: String,
    onLanguage: (String) -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    onClose: () -> Unit,
) = LanguageSheet(language, onLanguage, hazeState, onClose)

@Composable
private fun TabLayoutPreferences(
    layouts: Map<TabWidthClass, TabBarLayout>,
    window: Pair<TabWidthClass, Int>,
    onChange: (TabWidthClass, TabBarLayout) -> Unit,
    floatingTabs: Boolean,
    onFloatingTabs: (Boolean) -> Unit,
) {
    val (current, widthDp) = window
    var editing by rememberSaveable(current) { mutableStateOf<TabWidthClass?>(current) }
    PreferenceProfiles(
        stringResource(R.string.tabs_by_window_width),
        stringResource(R.string.tabs_by_window_width_detail),
        Modifier.testTag("Tab width profiles"),
    ) {
        TabWidthClass.entries.forEachIndexed { index, profile ->
            if (index > 0) ProfileDivider()
            TabLayoutProfile(
                profile,
                layouts[profile] ?: TabBarLayout(),
                profile == current,
                widthDp,
                profile == editing,
                onToggle = { editing = if (editing == profile) null else profile },
                onChange = { onChange(profile, it) },
            )
        }
        ProfileDivider()
        Row(
            Modifier.fillMaxWidth()
                .toggleable(
                    value = floatingTabs,
                    role = Role.Switch,
                    onValueChange = onFloatingTabs,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.tab_floating),
                    style = SettingsType.paneTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.tab_floating_detail),
                    style = SettingsType.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = floatingTabs, onCheckedChange = null)
        }
    }
}

@Composable
private fun PaneLayoutPreferences(
    layouts: Map<PaneOrientation, Boolean>,
    current: PaneOrientation,
    onChange: (PaneOrientation, Boolean) -> Unit,
    minimizeFloatingPanes: Boolean,
    onMinimizeFloatingPanes: (Boolean) -> Unit,
) {
    var editing by rememberSaveable(current) { mutableStateOf<PaneOrientation?>(current) }
    PreferenceProfiles(
        stringResource(R.string.panes_by_orientation),
        stringResource(R.string.panes_by_orientation_detail),
    ) {
        PaneOrientation.entries.forEachIndexed { index, orientation ->
            if (index > 0) ProfileDivider()
            val multiple = layouts[orientation] == true
            val expanded = orientation == editing
            PreferenceProfile(
                stringResource(orientation.titleRes),
                paneLayoutLabel(multiple),
                orientation == current,
                expanded,
                onToggle = { editing = if (expanded) null else orientation },
                icon = { PaneOrientationGlyph(orientation, expanded) },
            ) {
                PreferenceSegments(
                    listOf(false, true),
                    multiple,
                    { paneLayoutLabel(it) },
                    showCheck = true,
                    onSelect = { onChange(orientation, it) },
                )
            }
        }
        ProfileDivider()
        Row(
            Modifier.fillMaxWidth()
                .toggleable(
                    minimizeFloatingPanes,
                    role = Role.Switch,
                    onValueChange = onMinimizeFloatingPanes,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.minimize_floating_panes),
                    style = SettingsType.paneTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.minimize_floating_panes_detail),
                    style = SettingsType.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = minimizeFloatingPanes, onCheckedChange = null)
        }
    }
}

@Composable
private fun paneLayoutLabel(multiple: Boolean) =
    stringResource(if (multiple) R.string.multiple_panes else R.string.single_pane)

@Composable
private fun PreferenceProfiles(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val border = tintOver(colors.outlineVariant, colors.surface, .65f)
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(.5.dp, border),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                Modifier.padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = SettingsType.paneTitle, color = colors.primary)
                Text(description, style = SettingsType.detail, color = colors.onSurfaceVariant)
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = tintOver(colors.surfaceVariant, colors.surface, .25f),
                border = BorderStroke(.5.dp, border),
            ) {
                Column(Modifier.fillMaxWidth(), content = content)
            }
        }
    }
}

@Composable
private fun ProfileDivider() {
    val colors = MaterialTheme.colorScheme
    HorizontalDivider(
        thickness = .5.dp,
        color = tintOver(colors.outlineVariant, colors.surface, .65f),
    )
}

@Composable
private fun TabLayoutProfile(
    profile: TabWidthClass,
    layout: TabBarLayout,
    current: Boolean,
    widthDp: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (TabBarLayout) -> Unit,
) {
    val description =
        stringResource(
            when (profile) {
                TabWidthClass.NARROW -> R.string.tab_width_narrow_devices
                TabWidthClass.MEDIUM -> R.string.tab_width_medium_devices
                TabWidthClass.WIDE -> R.string.tab_width_wide_devices
            },
        )
    val summary =
        if (layout.side)
            stringResource(
                R.string.tab_summary_side_format,
                stringResource(layout.position.labelRes),
                stringResource(layout.style.labelRes).lowercase(),
            )
        else stringResource(R.string.tab_summary_bottom)
    PreferenceProfile(
        stringResource(profile.titleRes),
        summary,
        current,
        expanded,
        onToggle,
        description,
        icon = { TabWidthGlyph(profile, expanded) },
    ) {
        val range = stringResource(profile.rangeRes)
        Text(stringResource(R.string.position_label), style = SettingsType.paneTitle)
        PreferenceSegments(
            TabBarPosition.entries,
            layout.position,
            { stringResource(it.labelRes) },
        ) {
            onChange(layout.copy(position = it))
        }
        if (layout.side) {
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.sidebar_style), style = SettingsType.paneTitle)
            PreferenceSegments(
                TabRailStyle.entries,
                layout.style,
                { stringResource(it.labelRes) },
            ) {
                onChange(layout.copy(style = it))
            }
        }
        Text(
            if (current && widthDp > 0)
                stringResource(R.string.tab_width_current_range_format, range, widthDp)
            else range,
            style = SettingsType.paneDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreferenceProfile(
    title: String,
    summary: String,
    current: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    description: String? = null,
    icon: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stateLabel =
        stringResource(
            if (expanded) R.string.a11y_state_expanded else R.string.a11y_state_collapsed,
        )
    Column(
        Modifier.fillMaxWidth().background(if (expanded) colors.surface else Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = stateLabel }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(title, style = SettingsType.paneTitle)
                    if (current)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = colors.primaryContainer,
                            contentColor = colors.onPrimaryContainer,
                        ) {
                            Text(
                                stringResource(R.string.tab_width_now),
                                Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = SettingsType.paneDetail,
                            )
                        }
                }
                description?.let {
                    Text(it, style = SettingsType.detail, color = colors.onSurfaceVariant)
                }
                if (!expanded)
                    Text(summary, style = SettingsType.paneDetail, color = colors.onSurfaceVariant)
            }
            ChromeSymbol(
                ChromeIcon.CHEVRON,
                Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
                colors.onSurfaceVariant,
            )
        }
        if (expanded)
            Column(
                Modifier.fillMaxWidth().padding(start = 62.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> PreferenceSegments(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    showCheck: Boolean = false,
    onSelect: (T) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val active = option == selected
            SegmentedButton(
                selected = active,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = { if (showCheck) SegmentedButtonDefaults.Icon(active) },
                colors =
                    SegmentedButtonDefaults.colors(
                        activeContainerColor = colors.primaryContainer,
                        activeContentColor = colors.onPrimaryContainer,
                    ),
            ) {
                Text(
                    label(option),
                    style = SettingsType.paneDetail,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TabWidthGlyph(width: TabWidthClass, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    val strokeColor = if (active) colors.primary else colors.onSurfaceVariant
    Canvas(Modifier.size(40.dp, 32.dp)) {
        val deviceWidth =
            when (width) {
                TabWidthClass.NARROW -> 12.dp
                TabWidthClass.MEDIUM -> 20.dp
                TabWidthClass.WIDE -> 34.dp
            }.toPx()
        val height = 26.dp.toPx()
        val corner = CornerRadius(3.5.dp.toPx())
        val stroke = 1.5.dp.toPx()
        val origin = Offset((size.width - deviceWidth) / 2, (size.height - height) / 2)
        val device = Size(deviceWidth, height)
        if (active) drawRoundRect(colors.primaryContainer, origin, device, corner)
        drawRoundRect(strokeColor, origin, device, corner, style = Stroke(stroke))
        if (width != TabWidthClass.NARROW) {
            val hinge = if (width == TabWidthClass.WIDE) center.x else origin.x + 4.dp.toPx()
            val inset = if (width == TabWidthClass.WIDE) 0.dp else 3.dp
            drawLine(
                strokeColor,
                Offset(hinge, origin.y + inset.toPx()),
                Offset(hinge, origin.y + height - inset.toPx()),
                stroke,
            )
        }
    }
}

@Composable
private fun PaneOrientationGlyph(orientation: PaneOrientation, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.size(40.dp, 32.dp)) {
        val portrait = orientation == PaneOrientation.PORTRAIT
        val device =
            if (portrait) Size(18.dp.toPx(), 28.dp.toPx()) else Size(28.dp.toPx(), 18.dp.toPx())
        val origin = Offset((size.width - device.width) / 2, (size.height - device.height) / 2)
        val corner = CornerRadius(3.5.dp.toPx())
        if (active) drawRoundRect(colors.primaryContainer, origin, device, corner)
        drawRoundRect(
            if (active) colors.primary else colors.onSurfaceVariant,
            origin,
            device,
            corner,
            style = Stroke(1.5.dp.toPx()),
        )
    }
}

/** Pointer bookkeeping for the sheet: read from gesture callbacks, never from composition. */
private class SheetGesture {
    var handleZoneBottomPx = 0f
}
