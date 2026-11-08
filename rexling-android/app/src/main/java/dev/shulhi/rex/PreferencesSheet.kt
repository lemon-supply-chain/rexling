package dev.shulhi.rex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PreferencesSheet(onAppearance: ()->Unit,onFont: ()->Unit,onAlerts: ()->Unit,onAbout: ()->Unit,onClose: ()->Unit,
    shortcutGesture: ControlShortcutGesture=ControlShortcutGesture.HOLD,onShortcutGesture: (ControlShortcutGesture)->Unit={},onNeovim: ()->Unit={},
    arrowJoystickEnabled: Boolean=true,onArrowJoystick: (Boolean)->Unit={},codexTitleAttention: Boolean=false,onCodexTitleAttention: (Boolean)->Unit={},
    minimizeFloatingPanes: Boolean=false,onMinimizeFloatingPanes: (Boolean)->Unit={},language: String="system",onLanguage: ()->Unit={},
    terminalPadding: TerminalPadding=TerminalPadding.NORMAL,onTerminalPadding: (TerminalPadding)->Unit={},extendPadding: Boolean=true,onExtendPadding: (Boolean)->Unit={}) {
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max=680.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            SettingsHeader(stringResource(R.string.preferences),stringResource(R.string.close_preferences),onClose)
            SettingsSection(stringResource(R.string.section_display)) {
                SettingsRow(stringResource(R.string.appearance),stringResource(R.string.appearance_detail),SettingsGlyph.APPEARANCE,onAppearance)
                SettingsDivider()
                SettingsRow(stringResource(R.string.font_size),stringResource(R.string.font_size_detail),SettingsGlyph.FONT,onFont)
                SettingsDivider()
                SettingsRow(stringResource(R.string.language),when(language){"zh"->stringResource(R.string.language_zh);"en"->stringResource(R.string.language_en);else->stringResource(R.string.language_system)},SettingsGlyph.APPEARANCE,onLanguage)
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Terminal padding",style=SettingsType.row)
                    Text("Space around the terminal",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TerminalPadding.entries.forEach {option->
                            FilterChip(selected=terminalPadding==option,onClick={onTerminalPadding(option)},label={Text(option.label,style=SettingsType.row)})
                        }
                    }
                }
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(extendPadding,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onExtendPadding)
                    .padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Extend colors into padding",style=SettingsType.row)
                        Text(if(extendPadding) "Edge cell colors fill the padding, like Neovim's background" else "Padding keeps the theme background",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=extendPadding,onCheckedChange=null)
                }
            }
            SettingsSection(stringResource(R.string.section_panes)) {
                Row(Modifier.fillMaxWidth().toggleable(minimizeFloatingPanes,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onMinimizeFloatingPanes)
                    .padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.minimize_floating_panes),style=SettingsType.row)
                        Text(stringResource(R.string.minimize_floating_panes_detail),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=minimizeFloatingPanes,onCheckedChange=null)
                }
            }
            SettingsSection(stringResource(R.string.section_keyboard)) {
                Row(Modifier.fillMaxWidth().toggleable(arrowJoystickEnabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onArrowJoystick)
                    .padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.use_joystick),style=SettingsType.row)
                        Text(stringResource(if(arrowJoystickEnabled) R.string.use_joystick_detail_on else R.string.use_joystick_detail_off),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=arrowJoystickEnabled,onCheckedChange=null)
                }
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ctrl_shortcuts),style=SettingsType.row)
                    Text(stringResource(R.string.ctrl_shortcuts_detail),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        ControlShortcutGesture.entries.forEach {gesture->
                            FilterChip(selected=shortcutGesture==gesture,onClick={onShortcutGesture(gesture)},
                                label={Text(stringResource(if(gesture==ControlShortcutGesture.HOLD) R.string.ctrl_gesture_hold else R.string.ctrl_gesture_double_tap),style=SettingsType.row)})
                        }
                    }
                }
                SettingsDivider()
                SettingsRow(stringResource(R.string.neovim_shortcuts),stringResource(R.string.neovim_shortcuts_detail),SettingsGlyph.COMMAND,onNeovim)
            }
            SettingsSection(stringResource(R.string.section_connections)) {
                SettingsRow(stringResource(R.string.background_alerts),stringResource(R.string.background_alerts_detail),SettingsGlyph.ALERTS,onAlerts)
            }
            SettingsSection(stringResource(R.string.section_experimental)) {
                Row(Modifier.fillMaxWidth().toggleable(value=codexTitleAttention,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onCodexTitleAttention)
                    .padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.codex_title_attention),style=SettingsType.row)
                        Text(stringResource(R.string.codex_title_attention_detail),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=codexTitleAttention,onCheckedChange=null)
                }
            }
            SettingsSection(stringResource(R.string.section_app)) {
                SettingsRow(stringResource(R.string.about_rexling),stringResource(R.string.about_rexling_detail),SettingsGlyph.INFO,onAbout)
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
