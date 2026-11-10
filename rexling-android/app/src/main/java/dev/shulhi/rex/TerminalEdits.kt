package dev.shulhi.rex

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal data class TerminalRenameTarget(val window: String,val id: String,val tab: Boolean,val label: String)
internal fun terminalLabelError(value: String): String? = when {
    value.codePointCount(0,value.length)>128->"Use at most 128 characters"
    value.any {it.isISOControl()}->"Use a single-line name"
    else->null
}
private fun editableTerminalLabel(label: String)=label.takeUnless {
    it.lowercase() in listOf("shell","terminal","window") || Regex("(?i)(?:window|tab|block)[ _:-]+[0-9a-f-]+|Terminal \\d+").matches(it)
}.orEmpty()

@Composable internal fun TerminalRenameDialog(target: TerminalRenameTarget,enabled: Boolean,onSave: (String)->Boolean,onClose: ()->Unit) {
    var label by remember(target) {mutableStateOf(editableTerminalLabel(target.label))}
    var rejected by remember {mutableStateOf(false)}
    val error=terminalLabelError(label)
    AlertDialog(onDismissRequest=onClose,title={Text(if(target.tab) "Rename tab" else "Rename pane")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(label,{label=it;rejected=false},Modifier.fillMaxWidth(),label={Text("Name")},singleLine=true,isError=error!=null)
            Text(error ?: if(rejected) "This terminal changed. Review it and try again." else "Leave empty to use the automatic title.",style=SettingsType.detail,
                color=if(error!=null || rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },confirmButton={TextButton(enabled=enabled && error==null,onClick={if(onSave(label.trim())) onClose() else rejected=true}) {Text("Save")}},
        dismissButton={TextButton(onClick=onClose) {Text("Cancel")}})
}

@Composable internal fun DockPaneDialog(anchors: List<BlockItem>,programs: Map<String,ProgramIdentity>,titles: Map<String,String>,enabled: Boolean,
    onDock: (String,PaneSplit)->Boolean,onClose: ()->Unit) {
    var anchor by remember {mutableStateOf(anchors.firstOrNull()?.id.orEmpty())}
    var split by remember {mutableStateOf(PaneSplit.RIGHT)}
    var picker by remember {mutableStateOf(false)}
    var rejected by remember {mutableStateOf(false)}
    LaunchedEffect(anchors.map {it.id}) {if(anchors.none {it.id==anchor}) anchor=anchors.firstOrNull()?.id.orEmpty()}
    fun title(block: BlockItem)=(programs[block.id] ?: ProgramIdentity.Loading).paneTitle(block.label,titles[block.id].orEmpty())
    AlertDialog(onDismissRequest=onClose,title={Text("Dock pane")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Place beside",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(onClick={picker=true},enabled=enabled && anchors.isNotEmpty()) {Text(anchors.firstOrNull {it.id==anchor}?.let(::title) ?: "No docked pane in this tab",style=SettingsType.row)}
                DropdownMenu(picker,{picker=false}) {
                    anchors.forEach {block->DropdownMenuItem(text={Text(title(block),style=SettingsType.row)},onClick={anchor=block.id;picker=false})}
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PaneSplit.entries.forEach {item->FilterChip(selected=split==item,onClick={split=item},label={Text(item.label,style=SettingsType.row)})}
            }
            if(rejected) Text("The layout changed. Review it and try again.",style=SettingsType.detail,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(enabled=enabled && anchors.any {it.id==anchor},onClick={if(onDock(anchor,split)) onClose() else rejected=true}) {Text("Dock")}},
        dismissButton={TextButton(onClick=onClose) {Text("Cancel")}})
}
