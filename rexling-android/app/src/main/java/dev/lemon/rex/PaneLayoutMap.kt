package dev.lemon.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

private val paneMapInset = 3.dp
private val paneMapTarget = 24.dp

internal fun PanePlacement.isSelectableInPaneMap(mapWidth: Dp, mapHeight: Dp): Boolean =
    (mapWidth - paneMapInset * 2) * width - paneMapInset >= paneMapTarget &&
        (mapHeight - paneMapInset * 2) * height - paneMapInset >= paneMapTarget

@Composable internal fun PaneLayoutMap(window: WindowItem,selected: String,programs: Map<String,ProgramIdentity>,titles: Map<String,String>,enabled: Boolean,
    mapHeight: Dp = 88.dp,onSelect: (String)->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val scheme=MaterialTheme.colorScheme
    var showFloating by remember(window.id) {mutableStateOf(true)}
    val floatingCount=window.layout.count {it.floating}
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(floatingCount>0) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            TextButton(onClick={showFloating=!showFloating},contentPadding=PaddingValues(horizontal=6.dp),
                modifier=Modifier.height(32.dp).semantics {contentDescription=if(showFloating) context.getString(R.string.a11y_hide_floating_panes) else context.getString(R.string.a11y_show_floating_panes)}) {
                Text(if(showFloating) stringResource(R.string.label_hide_floating_format,floatingCount) else stringResource(R.string.label_show_floating_format,floatingCount),style=SettingsType.detail)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(mapHeight).clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerHigh).border(1.dp,scheme.outlineVariant,RoundedCornerShape(12.dp))
            .pointerInput(enabled) {
                // A held drag in the map must not turn into a pane-opening tap on release.
                if(enabled) detectTabDrag(start={true},move={},end={},cancel={})
            }
            .selectableGroup().semantics {contentDescription=context.getString(R.string.a11y_pane_layout)}.padding(paneMapInset)) {
            window.layout.filter {showFloating || !it.floating}.forEach {placement->
                val block=window.blocks.firstOrNull {it.id==placement.id}
                if(block!=null) key(placement.id) {
                val program=programs[block.id] ?: ProgramIdentity.Loading
                val title=program.paneTitle(block.label,titles[block.id].orEmpty())
                val active=placement.id==selected
                val width=(maxWidth*placement.width-paneMapInset).coerceAtLeast(.5.dp)
                val height=(maxHeight*placement.height-paneMapInset).coerceAtLeast(.5.dp)
                val usable=width>=paneMapTarget && height>=paneMapTarget
                val strokeColor=if(active) scheme.secondary else if(placement.floating) scheme.onSurfaceVariant else scheme.outlineVariant
                val shape=RoundedCornerShape(6.dp)
                Box(Modifier.offset(maxWidth*placement.x+1.5.dp,maxHeight*placement.y+1.5.dp).size(width,height)
                    .clip(shape).background((if(active) scheme.secondaryContainer else scheme.surface).copy(alpha=if(placement.floating) .78f else 1f))  // alpha-ok overlay translucency
                    .then(if(placement.floating) Modifier.drawBehind {
                        val stroke=(if(active) 2.dp else 1.dp).toPx();val inset=stroke/2
                        drawRoundRect(strokeColor,Offset(inset,inset),Size((size.width-stroke).coerceAtLeast(0f),(size.height-stroke).coerceAtLeast(0f)),CornerRadius(6.dp.toPx()),
                            style=Stroke(stroke,pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))
                    } else Modifier.border(if(active) 2.dp else 1.dp,strokeColor,shape))
                    .selectable(active,enabled=enabled && usable,role=Role.RadioButton,onClick={onSelect(placement.id)})
                    .semantics(mergeDescendants=true) {contentDescription=context.getString(R.string.a11y_layout_pane_format,title);stateDescription=listOfNotNull(if(active) context.getString(R.string.a11y_state_selected) else null,if(placement.floating) context.getString(R.string.a11y_state_floating) else null).joinToString(" · ")},
                    contentAlignment=Alignment.Center) {
                    val iconWidth=minOf(26.dp,width-6.dp,(height-6.dp)*61f/48f)
                    if(iconWidth>=12.dp) PaneProgramTile(program,Modifier.size(iconWidth,iconWidth*48f/61f).clearAndSetSemantics {},false,0f)
                }
            }}
        }
    }
}
