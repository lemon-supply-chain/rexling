package dev.shulhi.rex

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable internal fun SessionRow(target: SessionTarget,active: Boolean,last: Boolean,closing: Boolean,
    onSelect: ()->Unit,onClose: ()->Unit,onRename: ()->Unit={}) {
    val distance=with(LocalDensity.current){88.dp.toPx()}
    var position by remember {mutableFloatStateOf(0f)}
    var anchor by remember {mutableFloatStateOf(0f)}
    var dragging by remember {mutableStateOf(false)}
    var menu by remember {mutableStateOf(false)}
    val animated by animateFloatAsState(anchor,label="Session action reveal")
    val offset=if(dragging) position else animated
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if(abs(offset)>1f) MaterialTheme.colorScheme.errorContainer else Color.Transparent)) {
        if(abs(offset)>1f) TextButton(onClick=onClose,enabled=!closing,
            modifier=Modifier.align(if(offset>0) Alignment.CenterStart else Alignment.CenterEnd).width(88.dp)) {
            Text(stringResource(R.string.close),color=MaterialTheme.colorScheme.error,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth().offset {IntOffset(offset.roundToInt(),0)}
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=if(active) .8f else .35f))
            .draggable(rememberDraggableState {delta->position=(position+delta).coerceIn(-distance,distance)},Orientation.Horizontal,
                enabled=!closing,onDragStarted={dragging=true;position=anchor},onDragStopped={
                    anchor=if(abs(position)>distance*.35f) if(position>0) distance else -distance else 0f
                    dragging=false
                })
            .clickable(enabled=!closing){if(anchor!=0f) anchor=0f else onSelect()}
            .semantics {customActions=listOf(CustomAccessibilityAction("Rename session"){onRename();true},CustomAccessibilityAction("Close session"){onClose();true})}
            .heightIn(min=56.dp).padding(start=10.dp,end=2.dp,top=4.dp,bottom=4.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(6.dp).background(if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f)) {
                Text(target.session.label,style=SettingsType.row,fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text((target.detail?.let {stringResource(R.string.session_details_format,it.attachment,it.tabs,it.panes)} ?: stringResource(R.string.details_not_loaded)),
                    style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            when {closing->CircularProgressIndicator(Modifier.size(15.dp),strokeWidth=1.5.dp)
                active->Text(stringResource(R.string.session_current),color=MaterialTheme.colorScheme.primary,style=SettingsType.detail,fontWeight=FontWeight.SemiBold)
                last->Text(stringResource(R.string.session_previous),color=MaterialTheme.colorScheme.onSurfaceVariant,style=SettingsType.detail)}
            Box {
                ChromeButton(ChromeIcon.MORE,"Session actions for ${target.session.label} on ${target.server.label}",!closing){menu=true}
                DropdownMenu(menu,{menu=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                    TerminalMenuItem(stringResource(R.string.rename_session),ChromeIcon.EDIT,onClick={menu=false;onRename()})
                    TerminalMenuItem(stringResource(R.string.close_session),ChromeIcon.CLOSE,destructive=true,onClick={menu=false;onClose()})
                }
            }
        }
    }
}
