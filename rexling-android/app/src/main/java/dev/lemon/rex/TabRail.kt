package dev.lemon.rex

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class TabBarPosition(@androidx.annotation.StringRes val labelRes: Int, val label: String) {
    BOTTOM(R.string.tab_position_bottom, "Bottom"),
    LEFT(R.string.tab_position_left, "Left"),
    RIGHT(R.string.tab_position_right, "Right")
}
internal enum class TabRailStyle(@androidx.annotation.StringRes val labelRes: Int, val label: String) {
    ICONS(R.string.tab_rail_style_icons, "Icons"),
    TITLES(R.string.tab_rail_style_titles, "Icons and titles")
}

/** Where the tab bar sits, and how a side rail draws its tabs. Kept separately for folded and unfolded screens. */
internal data class TabBarLayout(val position: TabBarPosition=TabBarPosition.BOTTOM,val style: TabRailStyle=TabRailStyle.ICONS) {
    val side: Boolean get()=position!=TabBarPosition.BOTTOM
    fun encode()="${position.name},${style.name}"
    companion object {
        fun decode(raw: String?,default: TabBarLayout): TabBarLayout {
            val parts=raw?.split(',') ?: return default
            val position=TabBarPosition.entries.firstOrNull {it.name==parts.getOrNull(0)} ?: return default
            val style=TabRailStyle.entries.firstOrNull {it.name==parts.getOrNull(1)} ?: default.style
            return TabBarLayout(position,style)
        }
    }
}

/**
 * Which tab layout setting applies, by the app window's own width: the room the tabs actually have,
 * whether the window is a folded cover screen, the unfolded inner screen, or a split half of it.
 * On a Galaxy Z Fold: a portrait split half (~350dp) is narrow like a phone; the cover screen (~475dp)
 * and a landscape split half (~465dp) are medium; the inner screen held sideways (~933dp) is wide.
 */
internal enum class TabWidthClass(
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val detailRes: Int,
    val title: String,
    val detail: String,
    val key: String
) {
    NARROW(R.string.tab_width_narrow, R.string.tab_width_narrow_detail, "Narrow", "Under 440dp: phones, and a split half of the upright inner screen", "tab_layout_narrow"),
    MEDIUM(R.string.tab_width_medium, R.string.tab_width_medium_detail, "Medium", "440–760dp: the cover screen, a split half held sideways, and the upright inner screen", "tab_layout_medium"),
    WIDE(R.string.tab_width_wide, R.string.tab_width_wide_detail, "Wide", "760dp and wider: the inner screen held sideways", "tab_layout_wide");
    companion object {
        fun of(widthDp: Int): TabWidthClass = when {
            widthDp<440 -> NARROW
            widthDp<760 -> MEDIUM
            else -> WIDE
        }
    }
}

/** The current app window's width class, and its width in dp; both follow folding, rotation and split-screen resizes. */
@Composable internal fun currentTabWidth(): Pair<TabWidthClass,Int> {
    val width=LocalConfiguration.current.screenWidthDp
    return TabWidthClass.of(width) to width
}

/** A vertical tab list beside the terminal; [actions] (switcher, Activity, keyboard) sit at its foot. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TerminalTabRail(windows: List<WindowItem>,selectedId: String,enabled: Boolean,
    onSelect: (String)->Unit,panePrograms: (WindowItem)->List<ProgramIdentity>,selectedPane: (WindowItem)->Int,
    side: TabBarPosition,style: TabRailStyle,paneTitle: (WindowItem,Int)->String={_,_->""},paneWorking: (WindowItem)->Boolean={false},
    onClose: (WindowItem)->Unit={},onMove: (String,String?)->Boolean={_,_->false},reorderKey: String="",
    paneProgress: (WindowItem)->TerminalProgress?={null},tabAttention: (WindowItem)->Int={0},animateWorking: Boolean=true,
    onStyle: (TabRailStyle)->Unit={},extendUnderNav: Boolean=false,island: Boolean=false,islandBottom: androidx.compose.ui.unit.Dp=8.dp,
    actions: @Composable ()->Unit) {
    val titles=style==TabRailStyle.TITLES
    // Swipe across the rail toward the terminal to show titles, back toward the screen edge to hide them.
    // Resizes are debounced, so the animated width sends the pane only its final size.
    // Beside the multi-pane layout the rail is a floating card like the panes: rounded, inset, and
    // ending level with them; slightly narrower so the terminal keeps about the same width.
    val width by androidx.compose.animation.core.animateDpAsState(if(island) (if(titles) 144.dp else 48.dp) else if(titles) 152.dp else 52.dp,
        androidx.compose.animation.core.tween(180),label="Tab rail width")
    val outward=if(side==TabBarPosition.LEFT) 1f else -1f
    val threshold=with(androidx.compose.ui.platform.LocalDensity.current) {32.dp.toPx()}
    var swipe by remember {mutableFloatStateOf(0f)}
    // Gesture navigation treats edge swipes as Back. Android lets an app opt out for at most 200dp of
    // each edge, so claim the rail's lowest 200dp (its buttons and nearest tabs); the toggle below
    // always works.
    val exclusion=with(androidx.compose.ui.platform.LocalDensity.current) {200.dp.toPx()}
    val haptic=androidx.compose.ui.platform.LocalHapticFeedback.current
    val scheme=MaterialTheme.colorScheme
    val list=rememberLazyListState()
    var menuId by remember(reorderKey) {mutableStateOf<String?>(null)}
    val ids=windows.map {it.id}
    val index=ids.indexOf(selectedId)
    LaunchedEffect(ids) {menuId=null}
    LaunchedEffect(selectedId,reorderKey,index) {if(index>=0) list.animateScrollToItem(index)}
    val islandShape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxHeight()
        .then(if(island) Modifier.padding(start=if(side==TabBarPosition.LEFT) 8.dp else 0.dp,end=if(side==TabBarPosition.RIGHT) 8.dp else 0.dp,top=8.dp,bottom=islandBottom)
            .width(width).clip(islandShape).background(scheme.surface).border(1.5.dp,scheme.outline.copy(alpha=.25f),islandShape)
        else Modifier.width(width).background(scheme.surface)
            // Reaches the screen edge under the gesture handle; its buttons stay above it.
            .then(if(extendUnderNav) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier))
        .systemGestureExclusion {coordinates->
            val height=coordinates.size.height.toFloat()
            androidx.compose.ui.geometry.Rect(0f,(height-exclusion).coerceAtLeast(0f),coordinates.size.width.toFloat(),height)
        }
        .draggable(androidx.compose.foundation.gestures.rememberDraggableState {swipe+=it},androidx.compose.foundation.gestures.Orientation.Horizontal,
            onDragStarted={swipe=0f},onDragStopped={
                val next=when {
                    swipe*outward>threshold -> TabRailStyle.TITLES
                    swipe*outward< -threshold -> TabRailStyle.ICONS
                    else -> style
                }
                swipe=0f
                if(next!=style) {haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress);onStyle(next)}
            })
        .semantics {customActions=listOf(CustomAccessibilityAction(if(titles) "Show icons only" else "Show tab titles") {
            onStyle(if(titles) TabRailStyle.ICONS else TabRailStyle.TITLES);true})}
        .padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics {contentDescription="Tabs"},state=list,
            contentPadding=PaddingValues(horizontal=if(titles) 4.dp else 2.dp,vertical=2.dp),verticalArrangement=Arrangement.spacedBy(4.dp,Alignment.Bottom)) {
            itemsIndexed(windows,key={_,w->w.id}) {i,w->
                fun moveBy(delta: Int): Boolean {
                    val target=i+delta
                    if(target !in windows.indices) return false
                    val reordered=ids.toMutableList().apply {removeAt(i);add(target,w.id)}
                    return onMove(w.id,reordered.getOrNull(target+1))
                }
                val active=w.id==selectedId
                val programs=panePrograms(w)
                val pane=selectedPane(w)
                val program=programs.getOrNull(pane) ?: ProgramIdentity.Loading
                val title=program.tabTitle(w.label,w.blocks.getOrNull(pane)?.label.orEmpty(),i+1,paneTitle(w,pane))
                val unread=tabAttention(w)
                val progress=paneProgress(w)
                val busy=paneWorking(w) || progress?.busy==true
                val indicator=terminalProgressColor(progress)
                Box(Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null)) {
                    val workPhase=rememberWorkingPhase(animateWorking && active && if(progress!=null) progress.busy && progress.percent==null else busy)
                    Row(Modifier.fillMaxWidth().height(48.dp)
                        .background(if(active) scheme.surfaceVariant else Color.Transparent)
                        .drawWithContent {
                            drawContent()
                            // The bottom indicator bar is only drawn on the active tab.
                            if(active) {
                                val origin=Offset(0f,size.height-2.dp.toPx());val bar=Size(size.width,2.dp.toPx())
                                if(progress!=null) {
                                    drawWorkingBar(indicator.copy(alpha=.18f),origin,bar)
                                    val fill=progress.percent?.let {bar.copy(width=bar.width*it/100f)} ?: bar
                                    drawWorkingBar(indicator,origin,fill,phase=if(progress.busy && progress.percent==null && animateWorking) workPhase.value else null)
                                } else drawWorkingBar(indicator,origin,bar,phase=if(busy && animateWorking) workPhase.value else null)
                            }
                        }
                        .semantics(mergeDescendants=true) {contentDescription="Tab $title, ${program.label}"+(progress?.let {", ${it.description}"} ?: if(busy) ", working" else "")}
                        .semantics {
                            selected=active
                            if(unread>0) stateDescription="$unread new activity items"
                            if(enabled) customActions=buildList {
                                if(i>0) add(CustomAccessibilityAction("Move up") {moveBy(-1)})
                                if(i<windows.lastIndex) add(CustomAccessibilityAction("Move down") {moveBy(1)})
                            }
                        }
                        .combinedClickable(enabled=enabled,role=Role.Tab,onLongClickLabel="Tab options",
                            onLongClick={menuId=w.id},onClick={onSelect(w.id)})
                        .padding(horizontal=if(titles) 6.dp else 0.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=if(titles) Arrangement.spacedBy(4.dp) else Arrangement.Center) {
                        Box(contentAlignment=Alignment.Center) {
                            ProgramStack(programs,pane,fixedSlot=true)
                            if(unread>0 || busy) Box(Modifier.align(Alignment.TopEnd).offset(x=2.dp,y=(-2).dp).size(8.dp)
                                .border(1.5.dp,scheme.background,CircleShape)
                                .background(if(unread>0) scheme.primary else indicator,CircleShape)
                                .semantics {testTag=if(unread>0) "Tab unread dot ${w.id}" else "Tab working dot ${w.id}"})
                        }
                        if(titles) Text(title,Modifier.weight(1f),fontSize=12.sp,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis,
                            fontWeight=if(active) FontWeight.Bold else FontWeight.Normal,color=if(active) scheme.onSurface else scheme.onSurfaceVariant)
                    }
                    DropdownMenu(menuId==w.id,{menuId=null},containerColor=scheme.surface,tonalElevation=0.dp) {
                        TerminalMenuItem("Move up",ChromeIcon.UP,enabled=enabled && i>0,onClick={menuId=null;moveBy(-1)})
                        TerminalMenuItem("Move down",ChromeIcon.DOWN,enabled=enabled && i<windows.lastIndex,onClick={menuId=null;moveBy(1)})
                        TerminalMenuItem("Close tab",ChromeIcon.CLOSE,enabled=enabled,onClick={menuId=null;onClose(w)})
                    }
                }
            }
        }
        // Points the way the rail will go: toward the terminal to show titles, toward the edge to hide them.
        val towardTerminal=if(side==TabBarPosition.LEFT) -90f else 90f
        ChromeButton(ChromeIcon.CHEVRON,if(titles) "Show icons only" else "Show tab titles",
            modifier=Modifier.graphicsLayer {rotationZ=if(titles) -towardTerminal else towardTerminal}) {
            onStyle(if(titles) TabRailStyle.ICONS else TabRailStyle.TITLES)
        }
        Box(Modifier.padding(top=4.dp)) {actions()}
    }
}
