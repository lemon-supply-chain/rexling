package dev.lemon.rex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

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
    AlertDialog(onDismissRequest=onClose,title={Text(stringResource(if(target.tab) R.string.label_rename_tab else R.string.label_rename_pane))},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(label,{label=it;rejected=false},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.name))},singleLine=true,isError=error!=null)
            Text(error ?: stringResource(if(rejected) R.string.label_terminal_changed else R.string.label_leave_empty_hint),style=SettingsType.detail,
                color=if(error!=null || rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },confirmButton={TextButton(enabled=enabled && error==null,onClick={if(onSave(label.trim())) onClose() else rejected=true}) {Text(stringResource(R.string.save))}},
        dismissButton={TextButton(onClick=onClose) {Text(stringResource(R.string.cancel))}})
}

@Composable internal fun DockPaneSheet(window: WindowItem?,programs: Map<String,ProgramIdentity>,titles: Map<String,String>,enabled: Boolean,hazeState: HazeState?,
    onDock: (String,PaneSplit)->Boolean,onClose: ()->Unit) {
    val anchors=window?.tiledPanes().orEmpty()
    val ids=anchors.map {it.id}
    val placements=window?.layout.orEmpty().filter {!it.floating && it.id in ids}
    val map=window?.takeIf {ids.isNotEmpty() && ids.all {id->placements.any {it.id==id}}}
        ?.copy(blocks=anchors,layout=placements)
    var anchor by remember {mutableStateOf(anchors.firstOrNull()?.id.orEmpty())}
    var picker by remember {mutableStateOf(false)}
    var rejected by remember {mutableStateOf(false)}
    LaunchedEffect(ids) {if(anchor !in ids) anchor=ids.firstOrNull().orEmpty()}
    val selected=anchors.firstOrNull {it.id==anchor}
    fun title(block: BlockItem)=(programs[block.id] ?: ProgramIdentity.Loading).paneTitle(block.label,titles[block.id].orEmpty())
    fun choose(id: String) {anchor=id;picker=false;rejected=false}
    SettingsDrawer(stringResource(R.string.label_dock_pane),"Close dock pane",onClose,hazeState,surfaceColor=MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.weight(1f,fill=false).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal=16.dp).padding(bottom=12.dp),
            verticalArrangement=Arrangement.spacedBy(6.dp),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val mapWidth=minOf(maxWidth,176.dp)
                val mapHeight=mapWidth*.75f
                val showPicker=map==null || placements.any {!it.isSelectableInPaneMap(mapWidth,mapHeight)}
                Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(map!=null) Box(Modifier.width(mapWidth)) {
                        PaneLayoutMap(map,anchor,programs,titles,enabled,mapHeight,onSelect=::choose)
                    }
                    if(showPicker) Box {
                        OutlinedButton(onClick={picker=true},enabled=enabled && anchors.isNotEmpty()) {
                            Text(selected?.let(::title) ?: stringResource(R.string.label_no_docked_pane),style=SettingsType.detail,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        DropdownMenu(picker,{picker=false}) {
                            anchors.forEach {block->DropdownMenuItem(text={Text(title(block),style=SettingsType.detail)},onClick={choose(block.id)})}
                        }
                    } else if(selected!=null) {
                        Text("Dock next to ${title(selected)}",style=SettingsType.detail,maxLines=1,overflow=TextOverflow.Ellipsis,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.align(Alignment.CenterHorizontally).widthIn(max=320.dp).fillMaxWidth(),
                horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PaneSplit.entries.forEach {direction->
                    val right=direction==PaneSplit.RIGHT
                    FilledTonalButton(
                        onClick={if(onDock(anchor,direction)) onClose() else rejected=true},
                        enabled=enabled && selected!=null,modifier=Modifier.weight(1f),
                        contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),
                    ) {
                        ChromeSymbol(if(right) ChromeIcon.SPLIT_RIGHT else ChromeIcon.SPLIT_BELOW,
                            Modifier.size(16.dp),LocalContentColor.current)
                        Spacer(Modifier.width(6.dp))
                        Text(if(right) "Dock right" else "Dock below",style=SettingsType.row)
                    }
                }
            }
            if(rejected) Text(stringResource(R.string.label_layout_changed),style=SettingsType.detail,color=MaterialTheme.colorScheme.error)
        }
    }
}
