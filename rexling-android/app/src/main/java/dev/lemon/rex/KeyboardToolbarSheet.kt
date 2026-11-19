package dev.lemon.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState

@Composable internal fun KeyboardToolbarSheet(keys: List<ToolbarKey>,onSave: (List<ToolbarKey>)->Unit,onClose: ()->Unit,hazeState: HazeState?=null) {
    var encoded by rememberSaveable {mutableStateOf(ToolbarLayout.encode(keys))}
    val draft=ToolbarLayout.decode(encoded)
    val latestDraft by rememberUpdatedState(draft)
    var selected by rememberSaveable {mutableIntStateOf(0)}
    var dragged by remember {mutableStateOf<ToolbarKey?>(null)}
    var fromToolbar by remember {mutableStateOf(false)}
    var pointer by remember {mutableStateOf(Offset.Zero)}
    var origin by remember {mutableStateOf(Offset.Zero)}
    var toolbarBounds by remember {mutableStateOf(Rect.Zero)}
    var paletteBounds by remember {mutableStateOf(Rect.Zero)}
    val slotBounds=remember {mutableMapOf<ToolbarKey,Rect>()}
    val dropAreaDescription=stringResource(R.string.a11y_toolbar_drop_area)
    val availableButtonsDescription=stringResource(R.string.a11y_available_toolbar_buttons)
    val availableBounds=remember {mutableMapOf<ToolbarKey,Rect>()}
    val density=LocalDensity.current
    val rtl=LocalLayoutDirection.current==LayoutDirection.Rtl
    fun update(value: List<ToolbarKey>) {encoded=ToolbarLayout.encode(value);selected=selected.coerceIn(0,(value.size-1).coerceAtLeast(0))}
    fun targetIndex(): Int {
        val slots=latestDraft.mapIndexedNotNull {index,key->slotBounds[key]?.let {index to it}}
        val last=slots.lastOrNull()
        if(dragged !in latestDraft && latestDraft.size<ToolbarLayout.MAX_KEYS && last!=null &&
            (if(rtl) pointer.x<last.second.center.x else pointer.x>last.second.center.x)) return latestDraft.size
        return slots.minByOrNull {(_,bounds)->kotlin.math.abs(pointer.x-bounds.center.x)}?.first ?: 0
    }
    val overToolbar=dragged!=null && toolbarBounds.contains(pointer)
    val overPalette=dragged!=null && paletteBounds.contains(pointer)
    SettingsDrawer(stringResource(R.string.keyboard_toolbar),stringResource(R.string.close_keyboard_toolbar),onClose,hazeState,swipeToDismiss=false) {
        Box(Modifier.fillMaxWidth().onGloballyPositioned {origin=it.boundsInRoot().topLeft}
            .pointerInput(Unit) {
                detectTabDrag(start={position->
                    val root=position+origin
                    val current=latestDraft
                    val used=current.firstOrNull {slotBounds[it]?.contains(root)==true}
                    val available=if(paletteBounds.contains(root)) ToolbarKey.entries.firstOrNull {it !in current && availableBounds[it]?.contains(root)==true} else null
                    val item=used ?: available
                    if(item==null) false else {dragged=item;fromToolbar=used!=null;pointer=root;true}
                },move={delta->pointer+=delta},end={
                    val item=dragged
                    if(item!=null) {
                        val current=latestDraft
                        if(toolbarBounds.contains(pointer)) {
                            val target=targetIndex()
                            val result=ToolbarLayout.drop(current,item,target)
                            update(result);selected=result.indexOf(item).coerceAtLeast(0)
                        } else if(fromToolbar && paletteBounds.contains(pointer)) update(current-item)
                    }
                    dragged=null
                },cancel={dragged=null})
            }) {
            Column(Modifier.fillMaxWidth().heightIn(max=640.dp).padding(horizontal=16.dp).padding(top=8.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.keyboard_toolbar_hint),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.your_toolbar_format,draft.size,ToolbarLayout.MAX_KEYS),style=SettingsType.section,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().height(48.dp).onGloballyPositioned {toolbarBounds=it.boundsInRoot()}
                    .semantics {contentDescription=dropAreaDescription},horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(draft.isEmpty()) Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)),contentAlignment=Alignment.Center) {  // alpha-ok draft drop area
                        Text(stringResource(R.string.drag_buttons_here),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    draft.forEachIndexed {index,item->key(item.id) {
                        DisposableEffect(item) {onDispose {slotBounds.remove(item)}}
                        val highlighted=if(dragged!=null) overToolbar && targetIndex()==index else index==selected
                        val slotDescription=stringResource(R.string.a11y_toolbar_slot,index+1,item.label)
                        Box(Modifier.weight(1f).height(40.dp).onGloballyPositioned {slotBounds[item]=it.boundsInRoot()}
                            .clip(RoundedCornerShape(11.dp)).background(if(highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .border(if(highlighted) 1.dp else 0.dp,if(highlighted) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(11.dp))
                            .graphicsLayer {alpha=if(dragged==item) .3f else 1f}
                            .clickable(role=Role.Tab) {selected=index}
                            .semantics {
                                contentDescription=slotDescription;this.selected=index==selected
                                customActions=buildList {
                                    if(index>0) add(CustomAccessibilityAction("Move left") {update(ToolbarLayout.move(draft,index,-1));selected=index-1;true})
                                    if(index<draft.lastIndex) add(CustomAccessibilityAction("Move right") {update(ToolbarLayout.move(draft,index,1));selected=index+1;true})
                                    add(CustomAccessibilityAction("Remove from toolbar") {update(draft-item);true})
                                }
                            },contentAlignment=Alignment.Center) {ToolbarButtonFace(item)}
                    }}
                    ChromeSymbol(ChromeIcon.KEYBOARD,Modifier.padding(horizontal=8.dp).size(20.dp),MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(fromToolbar && dragged!=null) stringResource(R.string.drop_to_remove) else stringResource(R.string.available_buttons),Modifier.weight(1f),style=SettingsType.section,
                        color=if(overPalette && fromToolbar) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if(dragged==null && draft.isNotEmpty()) TextButton(onClick={update(draft.filterIndexed {index,_->index!=selected})},contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp),modifier=Modifier.height(28.dp)) {
                        Text(stringResource(R.string.remove_selected),style=SettingsType.detail)
                    }
                }
                LazyVerticalGrid(columns=GridCells.Fixed(4),modifier=Modifier.fillMaxWidth().weight(1f,fill=false).heightIn(min=96.dp,max=240.dp)
                    .onGloballyPositioned {paletteBounds=it.boundsInRoot()}
                    .clip(RoundedCornerShape(12.dp)).background(if(overPalette && fromToolbar) MaterialTheme.colorScheme.errorContainer.copy(alpha=.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f))  // alpha-ok palette background
                    .semantics {contentDescription=availableButtonsDescription},contentPadding=PaddingValues(8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(ToolbarKey.entries.filter {it !in draft},key={it.id}) {item->
                        val availableButtonDescription=stringResource(R.string.a11y_available_button,item.label)
                        DisposableEffect(item) {onDispose {availableBounds.remove(item)}}
                        Column(Modifier.height(56.dp).onGloballyPositioned {availableBounds[item]=it.boundsInRoot()}
                            .clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha=.6f))  // alpha-ok button surface
                            .graphicsLayer {alpha=if(dragged==item) .3f else 1f}
                            .clickable(role=Role.Button) {
                                val result=if(draft.size<ToolbarLayout.MAX_KEYS) draft+item else ToolbarLayout.assign(draft,selected,item)
                                update(result);selected=result.indexOf(item).coerceAtLeast(0)
                            }.semantics {contentDescription=availableButtonDescription},
                            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                            if(item.icon!=null) ChromeSymbol(item.icon,Modifier.size(19.dp),MaterialTheme.colorScheme.primary)
                            Text(item.label,style=SettingsType.detail,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(stringResource(R.string.keyboard_toolbar_drag_hint),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={update(ToolbarLayout.defaults)}) {Text(stringResource(R.string.reset_defaults),style=SettingsType.detail)}
                    Button(onClick={onSave(draft);onClose()}) {Text(stringResource(R.string.save),style=SettingsType.row)}
                }
            }
            dragged?.let {item->
                Box(Modifier.size(44.dp).graphicsLayer {
                    translationX=pointer.x-origin.x-with(density) {22.dp.toPx()}
                    translationY=pointer.y-origin.y-with(density) {22.dp.toPx()}
                    shadowElevation=with(density) {6.dp.toPx()};shape=RoundedCornerShape(11.dp);clip=true
                }.zIndex(10f).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center) {ToolbarButtonFace(item)}
            }
        }
    }
}

@Composable private fun ToolbarButtonFace(item: ToolbarKey) {
    if(item.icon!=null) ChromeSymbol(item.icon,Modifier.size(20.dp))
    else Text(item.label,style=SettingsType.detail,maxLines=2,overflow=TextOverflow.Ellipsis)
}
