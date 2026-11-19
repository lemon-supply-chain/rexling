package dev.lemon.rex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PreferencesSheet(onAppearance: ()->Unit,onFont: ()->Unit,onAlerts: ()->Unit,onAbout: ()->Unit,onClose: ()->Unit,
    shortcutGesture: ControlShortcutGesture=ControlShortcutGesture.HOLD,onShortcutGesture: (ControlShortcutGesture)->Unit={},onNeovim: ()->Unit={},
    arrowJoystickEnabled: Boolean=true,onArrowJoystick: (Boolean)->Unit={},enterSubmits: Boolean=false,onEnterSubmits: (Boolean)->Unit={},toolbarWithKeyboardOnly: Boolean=false,onToolbarWithKeyboardOnly: (Boolean)->Unit={},codexTitleAttention: Boolean=false,onCodexTitleAttention: (Boolean)->Unit={},
    minimizeFloatingPanes: Boolean=false,onMinimizeFloatingPanes: (Boolean)->Unit={},onNetwork: ()->Unit={},
    keepScreenAwake: KeepScreenAwake=KeepScreenAwake.SYSTEM,onKeepScreenAwake: (KeepScreenAwake)->Unit={},onToolbar: ()->Unit={},onControlItems: ()->Unit={},onSshKeys: ()->Unit={},
    keyboardSuggestionsEnabled: Boolean=false,onKeyboardSuggestions: (Boolean)->Unit={},
    language: String="system",onLanguage: ()->Unit={},
    terminalPadding: TerminalPadding=TerminalPadding.NORMAL,onTerminalPadding: (TerminalPadding)->Unit={},extendPadding: Boolean=true,onExtendPadding: (Boolean)->Unit={},
    predictiveEcho: PredictiveEcho=PredictiveEcho.SLOW,onPredictiveEcho: (PredictiveEcho)->Unit={},
    hazeState: dev.chrisbanes.haze.HazeState?=null) {
    // Built outside the sheet on purpose: the frost redraws when its host window draws, and inside the
    // sheet that host is the sheet's own window, which then redraws itself every frame while open.
    val frost=Modifier.drawerFrost(hazeState,BottomSheetDefaults.ExpandedShape)
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=androidx.compose.ui.graphics.Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,tonalElevation=0.dp,dragHandle=null,contentWindowInsets={WindowInsets(0,0,0,0)}) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(frost.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                DrawerHandle()
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.preferences),Modifier.weight(1f),style=SettingsType.row,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurface)
                    ChromeButton(ChromeIcon.CLOSE,stringResource(R.string.close_preferences),onClick=onClose)
                }
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(top=8.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SettingsSection(stringResource(R.string.section_display),compact=true) {
                SettingsRow(stringResource(R.string.appearance),stringResource(R.string.appearance_detail),SettingsGlyph.APPEARANCE,onAppearance)
                SettingsDivider()
                SettingsRow(stringResource(R.string.font_size),stringResource(R.string.font_size_detail),SettingsGlyph.FONT,onFont)
                SettingsDivider()
                SettingsRow(stringResource(R.string.language),when(language){"zh"->stringResource(R.string.language_zh);"en"->stringResource(R.string.language_en);else->stringResource(R.string.language_system)},SettingsGlyph.APPEARANCE,onLanguage)
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.terminal_padding),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.terminal_padding_detail),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TerminalPadding.entries.forEach {option->
                            val labelRes=when(option) {
                                TerminalPadding.NONE->R.string.terminal_padding_none
                                TerminalPadding.SMALL->R.string.terminal_padding_small
                                TerminalPadding.NORMAL->R.string.terminal_padding_normal
                            }
                            FilterChip(selected=terminalPadding==option,onClick={onTerminalPadding(option)},label={Text(stringResource(labelRes),style=SettingsType.row)})
                        }
                    }
                }
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(extendPadding,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onExtendPadding)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.extend_padding),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(if(extendPadding) R.string.extend_padding_detail_on else R.string.extend_padding_detail_off),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=extendPadding,onCheckedChange=null)
                }
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp)) {
                    Text(stringResource(R.string.keep_screen_awake),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.keep_screen_awake_desc),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    KeepScreenAwake.entries.forEach {mode->
                        Row(Modifier.fillMaxWidth().selectable(mode==keepScreenAwake,role=androidx.compose.ui.semantics.Role.RadioButton,onClick={onKeepScreenAwake(mode)}).padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=mode==keepScreenAwake,onClick=null)
                            Column(Modifier.padding(start=8.dp)) {
                                Text(stringResource(mode.labelRes),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(mode.detailRes),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            SettingsSection(stringResource(R.string.section_panes),compact=true) {
                Row(Modifier.fillMaxWidth().toggleable(minimizeFloatingPanes,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onMinimizeFloatingPanes)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.minimize_floating_panes),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.minimize_floating_panes_detail),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=minimizeFloatingPanes,onCheckedChange=null)
                }
            }
            SettingsSection(stringResource(R.string.section_keyboard),compact=true) {
                SettingsRow(stringResource(R.string.keyboard_toolbar),stringResource(R.string.keyboard_toolbar_detail),SettingsGlyph.COMMAND,onToolbar)
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(toolbarWithKeyboardOnly,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onToolbarWithKeyboardOnly)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.show_toolbar_only_with_keyboard),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.show_toolbar_only_with_keyboard_desc),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=toolbarWithKeyboardOnly,onCheckedChange=null)
                }
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(keyboardSuggestionsEnabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onKeyboardSuggestions)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.keyboard_suggestions),Modifier.weight(1f),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                    Switch(checked=keyboardSuggestionsEnabled,onCheckedChange=null)
                }
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(arrowJoystickEnabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onArrowJoystick)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.use_joystick),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(if(arrowJoystickEnabled) R.string.use_joystick_detail_on else R.string.use_joystick_detail_off),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=arrowJoystickEnabled,onCheckedChange=null)
                }
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(enterSubmits,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onEnterSubmits)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.keyboard_enter_sends),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(if(enterSubmits) R.string.keyboard_enter_sends_detail_on else R.string.keyboard_enter_sends_detail_off),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=enterSubmits,onCheckedChange=null)
                }
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ctrl_shortcuts),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.ctrl_shortcuts_detail),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        ControlShortcutGesture.entries.forEach {gesture->
                            FilterChip(selected=shortcutGesture==gesture,onClick={onShortcutGesture(gesture)},
                                label={Text(if(gesture==ControlShortcutGesture.HOLD) stringResource(R.string.ctrl_gesture_hold) else stringResource(R.string.ctrl_gesture_double_tap),style=SettingsType.row)})
                        }
                    }
                }
                SettingsDivider()
                SettingsRow(stringResource(R.string.ctrl_shortcut_buttons),stringResource(R.string.ctrl_shortcut_buttons_detail),SettingsGlyph.COMMAND,onControlItems)
                SettingsDivider()
                SettingsRow(stringResource(R.string.neovim_shortcuts),stringResource(R.string.neovim_shortcuts_detail),SettingsGlyph.COMMAND,onNeovim)
            }
            SettingsSection(stringResource(R.string.section_connections),compact=true) {
                SettingsRow(stringResource(R.string.network),stringResource(R.string.network_detail),SettingsGlyph.NETWORK,onNetwork)
                SettingsDivider()
                SettingsRow(stringResource(R.string.ssh_keys),stringResource(R.string.ssh_keys_detail),SettingsGlyph.KEY,onSshKeys)
                SettingsDivider()
                SettingsRow(stringResource(R.string.background_alerts),stringResource(R.string.background_alerts_detail),SettingsGlyph.ALERTS,onAlerts)
            }
            SettingsSection(stringResource(R.string.section_port_forwarding),compact=true) {
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp)) {
                    Text(stringResource(R.string.port_conflict_title),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.port_conflict_desc),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    PortConflictPolicy.entries.forEach {choice->
                        Row(Modifier.fillMaxWidth().selectable(PortForwards.policy==choice,role=androidx.compose.ui.semantics.Role.RadioButton,onClick={PortForwards.changePolicy(choice)}).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=PortForwards.policy==choice,onClick=null)
                            Text(stringResource(choice.labelRes),Modifier.padding(start=8.dp),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            SettingsSection(stringResource(R.string.section_experimental),compact=true) {
                Row(Modifier.fillMaxWidth().toggleable(value=codexTitleAttention,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onCodexTitleAttention)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.codex_title_attention),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.codex_title_attention_detail),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=codexTitleAttention,onCheckedChange=null)
                }
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.predictive_echo),style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.predictive_echo_summary),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        PredictiveEcho.entries.forEach {mode->
                            FilterChip(selected=predictiveEcho==mode,onClick={onPredictiveEcho(mode)},label={Text(stringResource(mode.labelRes),style=SettingsType.row)})
                        }
                    }
                }
            }
            SettingsSection(stringResource(R.string.section_app),compact=true) {
                SettingsRow(stringResource(R.string.about_rexling),stringResource(R.string.about_rexling_detail),SettingsGlyph.INFO,onAbout)
            }
        }
        }
        }
    }
}

@Composable internal fun LanguageDialog(language: String,onLanguage: (String)->Unit,onClose: ()->Unit) {
    AlertDialog(onDismissRequest=onClose,title={Text(stringResource(R.string.language),style=SettingsType.title)},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("system" to R.string.language_system,"zh" to R.string.language_zh,"en" to R.string.language_en).forEach {(key,labelRes)->
                Row(Modifier.fillMaxWidth().clickable {onLanguage(key);onClose()}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected=language==key,onClick={onLanguage(key);onClose()})
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(labelRes),style=SettingsType.row)
                }
            }
        }
    },confirmButton={TextButton(onClick=onClose){Text(stringResource(R.string.done))}})
}
