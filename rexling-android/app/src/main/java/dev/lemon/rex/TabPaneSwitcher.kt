package dev.lemon.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The Sessions page of the switcher: [current] names the session whose tabs and panes the other
 * pages show, and [content] is the page itself (see SessionsPage).
 */
internal class SwitcherSessions(val count: Int,val current: String,
    val onShown: ()->Unit={},val content: @Composable ()->Unit)

private enum class SwitcherPage(val labelRes: Int,val fallback: String) {
    SESSIONS(R.string.sessions,"Sessions"),
    TABS(0,"Tabs"),
    PANES(0,"Panes")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TabPaneSwitcher(
    windows: List<WindowItem>,selectedWindow: String,selectedBlock: String,
    programs: Map<String,ProgramIdentity>,titles: Map<String,String>,directories: Map<String,String>,
    selectedPane: (WindowItem)->Int,enabled: Boolean,canCreateTab: Boolean,canCreatePane: Boolean,
    onTab: (String)->Unit,onPane: (String)->Unit,onNewTab: ()->Unit,onNewPane: (String,PaneSplit)->Unit,
    onCloseTab: (WindowItem)->Unit,onClosePane: (BlockItem)->Unit,onClose: ()->Unit,initialPage: Int=0,
    onMoveTab: (String,String?)->Boolean={_,_->false},onMovePane: (String,String,String?)->Boolean={_,_,_->false},reorderKey: String="",
    onRenameTab: (String,String)->Boolean={_,_->false},onRenamePane: (String,String,String)->Boolean={_,_,_->false},
    onFloatPane: (String,String)->Boolean={_,_->false},onDockPane: (String,String,String,PaneSplit)->Boolean={_,_,_,_->false},error: String="",hazeState: dev.chrisbanes.haze.HazeState?=null,
    sessions: SwitcherSessions?=null,openSessions: Boolean=false,
) {
    var rename by remember(reorderKey) {mutableStateOf<TerminalRenameTarget?>(null)}
    var dock by remember(reorderKey) {mutableStateOf<Pair<String,String>?>(null)}
    var page by rememberSaveable(reorderKey) {mutableStateOf(when {
        sessions!=null && openSessions -> SwitcherPage.SESSIONS
        initialPage==1 -> SwitcherPage.PANES
        else -> SwitcherPage.TABS
    })}
    LaunchedEffect(page) {if(page==SwitcherPage.SESSIONS) sessions?.onShown?.invoke()}
    val pageState=rememberSaveableStateHolder()
    val current=windows.firstOrNull {it.id==selectedWindow}
    val panes=current?.blocks.orEmpty()
    val placements=current?.layout.orEmpty().associateBy {it.id}
    val tiledCount=current?.tiledPanes().orEmpty().size
    val tabs=page==SwitcherPage.TABS
    val hasLayout=panes.isNotEmpty() && panes.all {it.id in placements}
    var showLayout by rememberSaveable {mutableStateOf(false)}
    val rows=maxOf(windows.size,panes.size,(sessions?.count ?: 0)+1).coerceIn(3,7)
    val preferredHeight=(156.dp+60.dp*rows)*1.3f
    fun tabTitle(tab: WindowItem): String {
        val block=tab.blocks.getOrNull(selectedPane(tab))
        val program=programs[block?.id] ?: ProgramIdentity.Loading
        return program.tabTitle(tab.label,block?.label.orEmpty(),windows.indexOf(tab)+1,titles[block?.id].orEmpty())
    }
    val drawerSurface=Modifier.drawerFrost(hazeState,BottomSheetDefaults.ExpandedShape)
    if(dock==null) ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,tonalElevation=0.dp,
        dragHandle=null,contentWindowInsets={WindowInsets.safeDrawing.only(WindowInsetsSides.Top)}) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(drawerSurface.fillMaxWidth().height(minOf(preferredHeight,maxHeight*.82f)).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        DrawerHandle()
        Column(Modifier.fillMaxWidth().weight(1f).padding(bottom=4.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f),RoundedCornerShape(12.dp)).padding(4.dp).selectableGroup()) {
                (listOfNotNull(sessions?.let {SwitcherPage.SESSIONS to it.count})+
                    listOf(SwitcherPage.TABS to windows.size,SwitcherPage.PANES to panes.size)).forEach {(section,count)->
                    val active=page==section
                    val label=if(section.labelRes!=0) stringResource(section.labelRes) else section.fallback
                    Row(Modifier.weight(1f).fillMaxHeight().background(if(active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,RoundedCornerShape(9.dp))
                        .selectable(active,role=Role.Tab,onClick={page=section}),
                        horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                            Text(label,style=SettingsType.row,color=if(active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(count.toString(),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                }
            }
            if(error.isNotBlank()) Text(error,Modifier.padding(horizontal=16.dp,vertical=4.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.error,maxLines=2,overflow=TextOverflow.Ellipsis)
            pageState.SaveableStateProvider(page.name) {
                if(page==SwitcherPage.SESSIONS && sessions!=null) {sessions.content();return@SaveableStateProvider}
                val ids=if(tabs) windows.map {it.id} else panes.map {it.id}
                val list=rememberLazyListState()
                val drag=remember(page,reorderKey,if(tabs) "" else selectedWindow) {TabDrag()}
                var revealedRow by remember(page,reorderKey,selectedWindow) {mutableStateOf<String?>(null)}
                val haptic=LocalHapticFeedback.current
                val edge=with(LocalDensity.current) {32.dp.toPx()}
                val speed=with(LocalDensity.current) {420.dp.toPx()}
                fun move(id: String,before: String?)=if(tabs) onMoveTab(id,before) else onMovePane(selectedWindow,id,before)
                fun reveal(id: String,open: Boolean) {
                    if(open) revealedRow=id else if(revealedRow==id) revealedRow=null
                }
                fun moveBy(id: String,delta: Int): Boolean {
                    val index=ids.indexOf(id);val to=index+delta
                    if(index<0 || to !in ids.indices) return false
                    val order=ids.toMutableList().apply {removeAt(index);add(to,id)}
                    return move(id,order.getOrNull(to+1))
                }
                fun rowModifier(id: String)=Modifier
                    .zIndex(if(drag.id==id) 1f else 0f).graphicsLayer {translationY=drag.translation(id,list)}
                fun rowActions(id: String): List<CustomAccessibilityAction> =
                        if(enabled) buildList {
                            val index=ids.indexOf(id)
                            if(index>0) add(CustomAccessibilityAction("Move up") {moveBy(id,-1)})
                            if(index>=0 && index<ids.lastIndex) add(CustomAccessibilityAction("Move down") {moveBy(id,1)})
                        } else emptyList()
                LaunchedEffect(ids,enabled) {drag.cancel()}
                LaunchedEffect(drag.id) {
                    var previous=withFrameNanos {it}
                    while(drag.id!=null) {
                        val now=withFrameNanos {it};val dt=((now-previous)/1_000_000_000f).coerceAtMost(.05f);previous=now
                        if(!drag.moved) continue
                        val end=list.layoutInfo.viewportEndOffset.toFloat()
                        val velocity=when {
                            drag.pointer<edge -> -((edge-drag.pointer)/edge).coerceIn(0f,1f)
                            drag.pointer>end-edge -> ((drag.pointer-end+edge)/edge).coerceIn(0f,1f)
                            else -> 0f
                        }
                        if(velocity!=0f) {list.scrollBy(velocity*speed*dt);drag.cross(velocity,list)}
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    if(tabs || sessions!=null || hasLayout) Row(
                        Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=8.dp),
                        horizontalArrangement=Arrangement.spacedBy(8.dp),
                        verticalAlignment=Alignment.CenterVertically,
                    ) {
                        if(sessions!=null) SwitcherSessionLabel(sessions.current,Modifier.weight(1f)) {page=SwitcherPage.SESSIONS}
                        else Spacer(Modifier.weight(1f))
                        if(tabs) SwitcherAddButton("New tab",canCreateTab,onNewTab)
                        else if(hasLayout) FilterChip(
                            selected=showLayout,
                            onClick={
                                showLayout=!showLayout
                                if(showLayout) list.requestScrollToItem(0)
                            },
                            label={Text(if(showLayout) "Hide map" else "Show map",style=SettingsType.detail)},
                            leadingIcon={ChromeSymbol(ChromeIcon.SPLIT,Modifier.size(15.dp),LocalContentColor.current)},
                            shape=RoundedCornerShape(percent=50),
                            modifier=Modifier.height(40.dp),
                        )
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).selectableGroup().semantics {contentDescription=if(tabs) "Reorder tabs" else "Reorder panes"}
                        .pointerInput(enabled,ids,reorderKey,selectedWindow,page) {
                            if(enabled) detectTabDrag(
                                start={point->
                                    val started=drag.start(ids,list,point.y)
                                    if(started) {revealedRow=null;haptic.performHapticFeedback(HapticFeedbackType.LongPress)}
                                    started
                                },
                                move={amount->drag.move(amount.y,viewConfiguration.touchSlop,list)},
                                cancel={drag.cancel()},end={
                                    val id=drag.id
                                    if(id!=null && drag.changed()) move(id,drag.order.getOrNull(drag.order.indexOf(id)+1))
                                    drag.cancel()
                                })
                        },state=list,userScrollEnabled=drag.id==null,
                        contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(tabs) {
                            if(windows.isEmpty()) item {SwitcherEmpty("No tabs yet","Create a tab to start a terminal.")}
                            val displayed=if(drag.id==null) windows else drag.order.mapNotNull {id->windows.firstOrNull {it.id==id}}
                            items(displayed,key={it.id}) {tab->
                                val focus=selectedPane(tab)
                                val block=tab.blocks.getOrNull(focus)
                                val icons=tab.blocks.map {programs[it.id] ?: ProgramIdentity.Loading}
                                val title=tabTitle(tab)
                                val count="${tab.blocks.size} pane${if(tab.blocks.size==1) "" else "s"}"
                                val directory=directories[block?.id].orEmpty()
                                val subtitle=listOfNotNull(if(tab.id==selectedWindow) "Viewing" else null,count,directory.takeIf {it.isNotBlank()}).joinToString(" · ")
                                SwitcherItem(title,subtitle,icons,focus,tab.id==selectedWindow,
                                    "Tab $title",enabled,enabled && tab.blocks.isNotEmpty(),{onTab(tab.id)},
                                    Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null,placementSpec=if(drag.id==tab.id) null else androidx.compose.animation.core.spring()).then(rowModifier(tab.id)),
                                    rowActions(tab.id),revealedRow==tab.id,{reveal(tab.id,it)},listOf(
                                        SwitcherRowAction("Rename tab",ChromeIcon.EDIT) {rename=TerminalRenameTarget(tab.id,tab.id,true,tab.label)},
                                        SwitcherRowAction("Close tab",ChromeIcon.CLOSE,destructive=true) {onCloseTab(tab)},
                                    ))
                            }
                        } else {
                            if(panes.isEmpty()) item {SwitcherEmpty("No panes yet","Choose a tab or create a new one.")}
                            else if(hasLayout && showLayout) item(key="layout") {
                                PaneLayoutMap(current!!,selectedBlock,programs,titles,enabled,onSelect=onPane)
                                Spacer(Modifier.height(4.dp))
                            }
                            val displayed=if(drag.id==null) panes else drag.order.mapNotNull {id->panes.firstOrNull {it.id==id}}
                            items(displayed,key={it.id}) {block->
                                val program=programs[block.id] ?: ProgramIdentity.Loading
                                val title=program.paneTitle(block.label,titles[block.id].orEmpty())
                                val placement=placements[block.id]
                                val subtitle=listOfNotNull(if(block.id==selectedBlock) "Viewing" else null,if(placement?.floating==true) "Floating" else null,directories[block.id].orEmpty().ifBlank {program.label}).joinToString(" · ")
                                SwitcherItem(title,subtitle,listOf(program),0,block.id==selectedBlock,
                                    "Pane $title",enabled,enabled,{
                                        if(revealedRow==block.id) reveal(block.id,false) else onPane(block.id)
                                    },
                                    Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null,placementSpec=if(drag.id==block.id) null else androidx.compose.animation.core.spring()).then(rowModifier(block.id)),
                                    rowActions(block.id),revealedRow==block.id,{reveal(block.id,it)},buildList {
                                        add(SwitcherRowAction(stringResource(PaneSplit.RIGHT.labelRes),ChromeIcon.SPLIT_RIGHT,enabled=canCreatePane) {onNewPane(block.id,PaneSplit.RIGHT)})
                                        add(SwitcherRowAction(stringResource(PaneSplit.BELOW.labelRes),ChromeIcon.SPLIT_BELOW,enabled=canCreatePane) {onNewPane(block.id,PaneSplit.BELOW)})
                                        add(SwitcherRowAction("Rename pane",ChromeIcon.EDIT) {rename=TerminalRenameTarget(selectedWindow,block.id,false,block.label)})
                                        if(placement?.floating==true) {
                                            add(SwitcherRowAction("Dock pane",ChromeIcon.SPLIT,enabled=tiledCount>0) {dock=selectedWindow to block.id})
                                        } else if(placement!=null) {
                                            add(SwitcherRowAction("Float pane",ChromeIcon.FLOAT,enabled=tiledCount>1) {onFloatPane(selectedWindow,block.id)})
                                        }
                                        add(SwitcherRowAction("Close pane",ChromeIcon.CLOSE,destructive=true) {onClosePane(block)})
                                    },
                                    trailing={
                                        if(placement!=null) PanePositionMap(
                                            current?.layout.orEmpty(),block.id,
                                            Modifier.size(34.dp,22.dp).testTag("Pane position: ${block.id}"),
                                        )
                                    })
                            }
                        }
                    }
                }
            }
        }
    }
    }
    }
    rename?.let {target->
        val available=windows.firstOrNull {it.id==target.window}?.let {target.tab || it.blocks.any {b->b.id==target.id}}==true
        TerminalRenameDialog(target,enabled && available,onSave={label->
            if(target.tab) onRenameTab(target.id,label) else onRenamePane(target.window,target.id,label)
        },onClose={rename=null})
    }
    dock?.let {(window,id)->
        val tab=windows.firstOrNull {it.id==window}
        DockPaneSheet(tab,programs,titles,enabled && tab?.floatingPanes()?.any {it.id==id}==true,hazeState,
            onDock={anchor,split->onDockPane(window,id,anchor,split)},onClose={dock=null})
    }
}

@Composable private fun SwitcherEmpty(title: String,description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title,style=SettingsType.row)
        Text(description,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SwitcherItem(title: String,subtitle: String,programs: List<ProgramIdentity>,focus: Int,active: Boolean,
    description: String,enabled: Boolean,canOpen: Boolean,onSelect: ()->Unit,modifier: Modifier,
    reorderActions: List<CustomAccessibilityAction>,revealed: Boolean,onReveal: (Boolean)->Unit,actions: List<SwitcherRowAction>,
    trailing: @Composable ()->Unit={}) {
    val scheme=MaterialTheme.colorScheme
    SwitcherSwipeRow(revealed,onReveal,actions,enabled,modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).background(scheme.surfaceVariant.copy(alpha=if(active) .8f else .35f))
            .selectable(active,enabled=canOpen,role=Role.RadioButton,onClick=onSelect)
            .semantics {
                contentDescription=description
                stateDescription=if(active) "Selected" else ""
                customActions=if(enabled) reorderActions+actions.filter {it.enabled}.map {action->
                    CustomAccessibilityAction(action.label) {onReveal(false);action.run();true}
                } else emptyList()
            }.padding(start=8.dp,end=12.dp,top=4.dp,bottom=4.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(3.dp).height(22.dp).then(if(active) Modifier.background(scheme.primary,RoundedCornerShape(2.dp)) else Modifier))
            Box(Modifier.width(36.dp),contentAlignment=Alignment.Center) {ProgramStack(programs,focus,fixedSlot=true)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
                Text(title,style=SettingsType.row,color=if(active) scheme.primary else scheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(subtitle,style=SettingsType.detail,color=scheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
}

@Composable internal fun SwitcherAddButton(label: String,enabled: Boolean,onClick: ()->Unit) {
    FilledTonalButton(onClick=onClick,enabled=enabled,contentPadding=PaddingValues(horizontal=10.dp),
        modifier=Modifier.heightIn(min=40.dp)) {
        ChromeSymbol(ChromeIcon.ADD,Modifier.size(15.dp),LocalContentColor.current)
        Spacer(Modifier.width(4.dp))
        Text(label,style=SettingsType.section,maxLines=1)
    }
}

@Composable private fun SwitcherSessionLabel(current: String,modifier: Modifier,onClick: ()->Unit) {
    val scheme=MaterialTheme.colorScheme
    Row(modifier.clip(RoundedCornerShape(10.dp))
        .clickable(role=Role.Button,onClickLabel=stringResource(R.string.switch_session),onClick=onClick).padding(horizontal=4.dp,vertical=6.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.session_label),style=SettingsType.detail,color=scheme.onSurfaceVariant)
        Text(current.ifBlank {stringResource(R.string.none)},Modifier.weight(1f,fill=false),style=SettingsType.section,color=scheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)
        ChromeSymbol(ChromeIcon.CHEVRON,Modifier.size(14.dp).graphicsLayer {rotationZ=-90f},scheme.onSurfaceVariant)
    }
}
