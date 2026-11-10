package dev.shulhi.rex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Every profile, plus the menu settings they share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ShortcutProfilesSheet(profiles: List<ShortcutProfile>,onEdit: (String)->Unit,onNew: ()->Unit,onEnabled: (String,Boolean)->Unit,onClose: ()->Unit,
    layout: NeovimShortcutLayout=NeovimShortcutLayout.CIRCULAR,onLayout: (NeovimShortcutLayout)->Unit={},
    effect: NeovimShortcutEffect=NeovimShortcutEffect.FAN_OUT,onEffect: (NeovimShortcutEffect)->Unit={},
    hapticsEnabled: Boolean=true,onHaptics: (Boolean)->Unit={}) {
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max=680.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            SettingsHeader(stringResource(R.string.shortcuts),stringResource(R.string.close_shortcuts),onClose,subtitle=stringResource(R.string.shortcuts_subtitle))
            SettingsSection(stringResource(R.string.shortcut_profiles)) {
                profiles.forEachIndexed {index,profile->
                    if(index>0) SettingsDivider()
                    ShortcutProfileRow(profile,{onEdit(profile.id)},{onEnabled(profile.id,it)})
                }
                if(profiles.isNotEmpty()) SettingsDivider()
                SettingsRow(stringResource(R.string.new_profile),stringResource(R.string.new_profile_detail),SettingsGlyph.COMMAND,onNew)
            }
            SettingsSection(stringResource(R.string.shortcut_menu)) {
                SettingsChoiceRow(stringResource(R.string.menu_layout),NeovimShortcutLayout.entries,layout,{
                    stringResource(if(it==NeovimShortcutLayout.GRID) R.string.layout_grid else R.string.layout_circular)
                },onLayout)
                if(layout==NeovimShortcutLayout.CIRCULAR) {
                    SettingsDivider()
                    SettingsChoiceRow(stringResource(R.string.opening_effect),NeovimShortcutEffect.entries,effect,{
                        stringResource(when(it) {
                            NeovimShortcutEffect.FAN_OUT->R.string.effect_fan_out
                            NeovimShortcutEffect.SPIN->R.string.effect_spin
                            NeovimShortcutEffect.EXPAND->R.string.effect_expand
                            NeovimShortcutEffect.NONE->R.string.effect_none
                        })
                    },onEffect,subtitle=stringResource(when(effect) {
                        NeovimShortcutEffect.FAN_OUT->R.string.effect_fan_out_detail
                        NeovimShortcutEffect.SPIN->R.string.effect_spin_detail
                        NeovimShortcutEffect.EXPAND->R.string.effect_expand_detail
                        NeovimShortcutEffect.NONE->R.string.effect_none_detail
                    }))
                    SettingsDivider()
                    SettingsToggleRow(stringResource(R.string.selection_haptics),hapticsEnabled,onHaptics,subtitle=stringResource(R.string.selection_haptics_detail))
                }
            }
            Text(stringResource(R.string.shortcuts_hint),
                style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ShortcutProfileRow(profile: ShortcutProfile,onClick: ()->Unit,onEnabled: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().clickable(role=Role.Button,onClick=onClick).padding(start=16.dp,end=8.dp,top=12.dp,bottom=12.dp)
        .semantics {contentDescription="Edit ${profile.name} profile"},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(profile.name,style=SettingsType.row)
            Text(profile.matchText,style=SettingsType.detail.copy(fontFamily=MonoUi),color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(shortcutCount(profile.items),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked=profile.enabled,onCheckedChange=onEnabled,modifier=Modifier.semantics {
            contentDescription="Show ${profile.name} shortcuts";stateDescription=if(profile.enabled) "On" else "Off"})
    }
}

internal fun shortcutCount(items: List<NeovimShortcut>): String {
    val groups=items.map {it.group}.filter {it.isNotEmpty()}.distinct().size
    val count=if(items.size==1) "1 shortcut" else "${items.size} shortcuts"
    return when(groups) {0->count;1->"$count · 1 group";else->"$count · $groups groups"}
}

/** Edits one profile, or creates one when [profile] is null. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ShortcutProfileSheet(profile: ShortcutProfile?,onSave: (name: String,match: String,prefix: String,enter: ShortcutEnter,shortcuts: String)->String?,
    onClose: ()->Unit,onDelete: (()->Unit)?=null,defaults: List<NeovimShortcut>?=null) {
    var name by rememberSaveable {mutableStateOf(profile?.name.orEmpty())}
    var match by rememberSaveable {mutableStateOf(profile?.matchText.orEmpty())}
    var prefix by rememberSaveable {mutableStateOf(profile?.prefix.orEmpty())}
    var enter by rememberSaveable {mutableStateOf(profile?.enter ?: ShortcutEnter.TEXT)}
    var draft by rememberSaveable {mutableStateOf(NeovimShortcuts.editorText(profile?.items.orEmpty()))}
    var error by remember {mutableStateOf<String?>(null)}
    var deleting by remember {mutableStateOf(false)}
    val defaultTitle=stringResource(R.string.new_profile)
    val title=profile?.name ?: defaultTitle
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max=680.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            SettingsHeader(title,stringResource(R.string.close_title_format,title),onClose)
            SettingsTextField(stringResource(R.string.name),name,{name=it;error=null})
            SettingsTextField(stringResource(R.string.programs),match,{match=it;error=null},mono=true,
                supporting=stringResource(R.string.programs_hint))
            SettingsTextField(stringResource(R.string.keys_sent_first),prefix,{prefix=it;error=null},mono=true,
                supporting=stringResource(R.string.keys_sent_first_hint))
            SettingsSection(stringResource(R.string.enter_section)) {
                SettingsChoiceRow(stringResource(R.string.press_enter_after),ShortcutEnter.entries,enter,{
                    stringResource(when(it) {
                        ShortcutEnter.NEVER->R.string.enter_never
                        ShortcutEnter.TEXT->R.string.enter_text
                        ShortcutEnter.EX_COMMANDS->R.string.enter_ex_commands
                    })
                },{enter=it},subtitle=stringResource(when(enter) {
                    ShortcutEnter.NEVER->R.string.enter_never_detail
                    ShortcutEnter.TEXT->R.string.enter_text_detail
                    ShortcutEnter.EX_COMMANDS->R.string.enter_ex_commands_detail
                }))
            }
            SettingsTextField(stringResource(R.string.shortcuts),draft,{draft=it;error=null},mono=true,singleLine=false,minLines=6,maxLines=12,isError=error!=null)
            error?.let {Text(it,style=SettingsType.detail,color=MaterialTheme.colorScheme.error)}
            Text(stringResource(R.string.shortcut_syntax_hint),
                style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                if(onDelete!=null) TextButton(onClick={deleting=true}) {Text(stringResource(R.string.delete),color=MaterialTheme.colorScheme.error)}
                if(defaults!=null) TextButton(onClick={draft=NeovimShortcuts.editorText(defaults);error=null}) {Text(stringResource(R.string.reset_shortcuts))}
                Spacer(Modifier.weight(1f))
                Button(onClick={error=onSave(name,match,prefix,enter,draft);if(error==null) onClose()}) {Text(stringResource(R.string.save))}
            }
        }
    }
    if(deleting && onDelete!=null) SettingsConfirmDialog(stringResource(R.string.delete_profile_title,title),stringResource(R.string.delete_profile_confirm),stringResource(R.string.delete),
        onConfirm={deleting=false;onDelete();onClose()},onDismiss={deleting=false})
}

/** Asks before a shortcut marked with ! is sent. */
@Composable internal fun ShortcutConfirmDialog(profile: ShortcutProfile,item: NeovimShortcut,onConfirm: ()->Unit,onDismiss: ()->Unit) {
    SettingsConfirmDialog(stringResource(R.string.run_shortcut_title,item.label),stringResource(R.string.run_shortcut_confirm,profile.name),stringResource(R.string.run),onConfirm,onDismiss,detail=item.keys)
}
