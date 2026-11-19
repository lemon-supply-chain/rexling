package dev.lemon.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun SessionRow(target: SessionTarget,active: Boolean,last: Boolean,closing: Boolean,
    onSelect: ()->Unit,onClose: ()->Unit,onRename: ()->Unit={}) {
    var menu by remember {mutableStateOf(false)}
    // No swipe-to-close: rows sit in a horizontal pager, so sideways swipes always change page.
    // Close and Rename stay in the actions menu.
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=if(active) .8f else .35f))  // alpha-ok session row background
            .clickable(enabled=!closing,onClick=onSelect)
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
                    TerminalMenuItem(stringResource(R.string.close_session),ChromeIcon.CLOSE,enabled=true,destructive=true,onClick={menu=false;onClose()})
                }
            }
        }
    }
}
