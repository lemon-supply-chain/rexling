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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable internal fun PaneLayoutMap(window: WindowItem,selected: String,programs: Map<String,ProgramIdentity>,titles: Map<String,String>,enabled: Boolean,onSelect: (String)->Unit) {
    val scheme=MaterialTheme.colorScheme
    var showFloating by remember(window.id) {mutableStateOf(true)}
    val floatingCount=window.layout.count {it.floating}
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(floatingCount>0) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            TextButton(onClick={showFloating=!showFloating},contentPadding=PaddingValues(horizontal=6.dp),
                modifier=Modifier.height(32.dp).semantics {contentDescription=if(showFloating) "Hide floating panes" else "Show floating panes"}) {
                Text(if(showFloating) "Hide floating ($floatingCount)" else "Show floating ($floatingCount)",style=SettingsType.detail)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha=.45f)).border(1.dp,scheme.outlineVariant,RoundedCornerShape(12.dp))
            .padding(3.dp).selectableGroup().semantics {contentDescription="Pane layout"}) {
            window.layout.filter {showFloating || !it.floating}.forEach {placement->
                val block=window.blocks.firstOrNull {it.id==placement.id}
                if(block!=null) key(placement.id) {
                val program=programs[block.id] ?: ProgramIdentity.Loading
                val title=program.paneTitle(block.label,titles[block.id].orEmpty())
                val active=placement.id==selected
                val width=(maxWidth*placement.width-3.dp).coerceAtLeast(.5.dp)
                val height=(maxHeight*placement.height-3.dp).coerceAtLeast(.5.dp)
                val usable=width>=24.dp && height>=24.dp
                val strokeColor=if(active) scheme.primary else if(placement.floating) scheme.onSurfaceVariant else scheme.outlineVariant
                val shape=RoundedCornerShape(6.dp)
                Box(Modifier.offset(maxWidth*placement.x+1.5.dp,maxHeight*placement.y+1.5.dp).size(width,height)
                    .clip(shape).background((if(active) scheme.primaryContainer else scheme.surface).copy(alpha=if(placement.floating) .78f else 1f))
                    .then(if(placement.floating) Modifier.drawBehind {
                        val stroke=(if(active) 2.dp else 1.dp).toPx();val inset=stroke/2
                        drawRoundRect(strokeColor,Offset(inset,inset),Size((size.width-stroke).coerceAtLeast(0f),(size.height-stroke).coerceAtLeast(0f)),CornerRadius(6.dp.toPx()),
                            style=Stroke(stroke,pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))
                    } else Modifier.border(if(active) 2.dp else 1.dp,strokeColor,shape))
                    .selectable(active,enabled=enabled && usable,role=Role.RadioButton,onClick={onSelect(placement.id)})
                    .semantics(mergeDescendants=true) {contentDescription="Layout pane $title";stateDescription=listOfNotNull(if(active) "Selected" else null,if(placement.floating) "Floating" else null).joinToString(" · ")},
                    contentAlignment=Alignment.Center) {
                    val iconWidth=minOf(26.dp,width-6.dp,(height-6.dp)*61f/48f)
                    if(iconWidth>=12.dp) PaneProgramTile(program,Modifier.size(iconWidth,iconWidth*48f/61f).clearAndSetSemantics {},false,0f)
                }
            }}
        }
    }
}
