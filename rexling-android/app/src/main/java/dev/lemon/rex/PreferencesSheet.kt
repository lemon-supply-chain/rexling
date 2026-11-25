package dev.lemon.rex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
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
    multiPaneUnfolded: Boolean = false,
    onMultiPaneUnfolded: (Boolean) -> Unit = {},
    tabLayouts: Map<TabWidthClass, TabBarLayout> = emptyMap(),
    tabWidth: Pair<TabWidthClass, Int> = TabWidthClass.NARROW to 0,
    onTabLayout: (TabWidthClass, TabBarLayout) -> Unit = { _, _ -> },
    floatingTabs: Boolean = false,
    onFloatingTabs: (Boolean) -> Unit = {},
    compactTopBar: Boolean = false,
    onCompactTopBar: (Boolean) -> Unit = {},
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
    // Read only from pointer and nested-scroll callbacks, so this stays out of composition state.
    val gesture = remember { SheetGesture() }
    var isPullingHandle by remember { mutableStateOf(false) }
    val sheetHolder = remember { SheetStateHolder() }
    val isExpanded by remember {
        derivedStateOf { sheetHolder.state?.currentValue == SheetValue.Expanded }
    }
    // Remembered because rememberModalBottomSheetState keys its SheetState on this lambda.
    val confirmValueChange = remember {
        { next: SheetValue ->
            next == SheetValue.Expanded || !isExpanded || gesture.startedInHandleZone
        }
    }
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = false,
            confirmValueChange = confirmValueChange,
        )
    sheetHolder.state = sheetState
    val blockExpandedBodyDismiss = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset =
                if (isExpanded && !gesture.startedInHandleZone && available.y > 0f)
                    Offset(0f, available.y)
                else Offset.Zero

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity =
                if (isExpanded && !gesture.startedInHandleZone && available.y > 0f)
                    Velocity(0f, available.y)
                else Velocity.Zero
        }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        dragHandle = null,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top) },
    ) {
        // Material3 routes back through SheetState.hide(), which the guard above refuses while the
        // sheet is expanded, so back needs its own path. Registered inside the sheet content to
        // follow Material3's predictive-back handler and therefore win.
        BackHandler(enabled = isExpanded) { onClose() }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val contentMaxHeight = (maxHeight - 64.dp).coerceAtLeast(200.dp)
                Column(
                    frost.fillMaxWidth().pointerInput(Unit) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down =
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                            val inHandle = down.position.y <= gesture.handleZoneBottomPx
                            gesture.startedInHandleZone = inHandle
                            val pointerId = down.id
                            val startY = down.position.y
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                    if (inHandle && change != null && change.pressed) {
                                        if (change.position.y - startY > touchSlop) {
                                            isPullingHandle = true
                                        }
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
                            active = isPullingHandle,
                            modifier = Modifier.testTag("Preferences drawer handle"),
                        )
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(start = 16.dp, end = 4.dp),
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
                                onClick = onClose,
                            )
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .testTag("Preferences body area")
                            .pointerInput(Unit) {
                                val touchSlop = viewConfiguration.touchSlop
                                awaitEachGesture {
                                    val down =
                                        awaitFirstDown(
                                            requireUnconsumed = false,
                                            pass = PointerEventPass.Main,
                                        )
                                    val pointerId = down.id
                                    val startY = down.position.y
                                    var blockedDrag = false
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        val change =
                                            event.changes.firstOrNull { it.id == pointerId }
                                        if (change != null && change.pressed) {
                                            if (
                                                !blockedDrag &&
                                                    isExpanded &&
                                                    !gesture.startedInHandleZone &&
                                                    !change.isConsumed &&
                                                    kotlin.math.abs(change.position.y - startY) >
                                                        touchSlop
                                            ) {
                                                blockedDrag = true
                                            }
                                            if (blockedDrag) {
                                                change.consume()
                                            }
                                        }
                                        if (event.changes.none { it.pressed }) break
                                    }
                                }
                            }
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                            ),
                    ) {
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(max = contentMaxHeight)
                                .nestedScroll(blockExpandedBodyDismiss)
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
                                                        androidx.compose.ui.semantics.Role
                                                            .RadioButton,
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
                                                    color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            SettingsSection(stringResource(R.string.section_tabs), compact = true) {
                                Text(
                                    stringResource(R.string.section_tabs_desc),
                                    Modifier.padding(horizontal = 16.dp).padding(top = 10.dp),
                                    style = SettingsType.detail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    Modifier.fillMaxWidth()
                                        .toggleable(
                                            value = floatingTabs,
                                            role = androidx.compose.ui.semantics.Role.Switch,
                                            onValueChange = onFloatingTabs,
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
                                            stringResource(R.string.tab_floating),
                                            style = SettingsType.row,
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
                                SettingsDivider()
                                TabWidthClass.entries.forEachIndexed { i, each ->
                                    if (i > 0) SettingsDivider()
                                    TabLayoutChoice(
                                        each,
                                        tabLayouts[each] ?: TabBarLayout(),
                                        current =
                                            if (each == tabWidth.first) tabWidth.second else null,
                                    ) {
                                        onTabLayout(each, it)
                                    }
                                }
                            }
                            SettingsSection(
                                stringResource(R.string.section_panes),
                                compact = true,
                            ) {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.panes_unfolded_title),
                                        style = SettingsType.row,
                                    )
                                    Text(
                                        stringResource(R.string.panes_unfolded_desc),
                                        style = SettingsType.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = !multiPaneUnfolded,
                                            onClick = { onMultiPaneUnfolded(false) },
                                            label = {
                                                Text(
                                                    stringResource(R.string.single_pane),
                                                    style = SettingsType.row,
                                                )
                                            },
                                        )
                                        FilterChip(
                                            selected = multiPaneUnfolded,
                                            onClick = { onMultiPaneUnfolded(true) },
                                            label = {
                                                Text(
                                                    stringResource(R.string.multiple_panes),
                                                    style = SettingsType.row,
                                                )
                                            },
                                        )
                                    }
                                }
                                SettingsDivider()
                                Row(
                                    Modifier.fillMaxWidth()
                                        .toggleable(
                                            minimizeFloatingPanes,
                                            role = androidx.compose.ui.semantics.Role.Switch,
                                            onValueChange = onMinimizeFloatingPanes,
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
                                            stringResource(R.string.minimize_floating_panes),
                                            style = SettingsType.row,
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
                                        style = SettingsType.row,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        stringResource(R.string.ctrl_shortcuts_detail),
                                        style = SettingsType.detail,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ControlShortcutGesture.entries.forEach { gesture ->
                                            FilterChip(
                                                selected = shortcutGesture == gesture,
                                                onClick = { onShortcutGesture(gesture) },
                                                label = {
                                                    Text(
                                                        if (gesture == ControlShortcutGesture.HOLD)
                                                            stringResource(
                                                                R.string.ctrl_gesture_hold,
                                                            )
                                                        else
                                                            stringResource(
                                                                R.string.ctrl_gesture_double_tap,
                                                            ),
                                                        style = SettingsType.row,
                                                    )
                                                },
                                            )
                                        }
                                    }
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
                                                        androidx.compose.ui.semantics.Role
                                                            .RadioButton,
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

/** Tab bar position, and the side rail's style, for one screen posture. */
@Composable
private fun TabLayoutChoice(
    width: TabWidthClass,
    layout: TabBarLayout,
    current: Int?,
    onChange: (TabBarLayout) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(width.titleRes), style = SettingsType.row)
            if (current != null)
                Text(
                    stringResource(R.string.this_window_format, current),
                    style = SettingsType.detail,
                    color = MaterialTheme.colorScheme.primary,
                )
        }
        Text(
            stringResource(width.detailRes),
            style = SettingsType.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabBarPosition.entries.forEach { position ->
                FilterChip(
                    selected = layout.position == position,
                    onClick = { onChange(layout.copy(position = position)) },
                    label = { Text(stringResource(position.labelRes), style = SettingsType.row) },
                )
            }
        }
        if (layout.side)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabRailStyle.entries.forEach { style ->
                    FilterChip(
                        selected = layout.style == style,
                        onClick = { onChange(layout.copy(style = style)) },
                        label = { Text(stringResource(style.labelRes), style = SettingsType.row) },
                    )
                }
            }
    }
}

/** Pointer bookkeeping for the sheet: read from gesture callbacks, never from composition. */
private class SheetGesture {
    var startedInHandleZone = false
    var handleZoneBottomPx = 0f
}

/**
 * The confirm callback needs the sheet state before `rememberModalBottomSheetState` returns it; a
 * plain holder keeps that circular reference out of composition state.
 */
@OptIn(ExperimentalMaterial3Api::class)
private class SheetStateHolder {
    var state: SheetState? = null
}
