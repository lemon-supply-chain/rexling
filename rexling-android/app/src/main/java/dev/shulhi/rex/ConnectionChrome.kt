package dev.shulhi.rex

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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal fun connectionMessage(phase: String,network: Boolean?): String? = when {
    phase !in listOf("connecting","reconnecting","error")->null
    network==false->"Waiting for network · input paused"
    phase=="connecting"->"Connecting…"
    else->"Reconnecting · input paused"
}

@Composable internal fun connectionNoticeText(phase: String,network: Boolean?): String? = when {
    phase !in listOf("connecting","reconnecting","error")->null
    network==false->stringResource(R.string.waiting_network_input_paused)
    phase=="connecting"->stringResource(R.string.connecting)
    else->stringResource(R.string.reconnecting_input_paused)
}

@Composable internal fun ConnectionNotice(phase: String,network: Boolean?,onRetry: ()->Unit,modifier: Modifier=Modifier) {
    connectionNoticeText(phase,network)?.let {message->
        Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(start=12.dp,end=6.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(message,Modifier.weight(1f),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick=onRetry,enabled=network!=false,contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)) {Text(stringResource(R.string.retry_now),fontSize=11.sp)}
        }
    }
}
@Composable internal fun OtherOwnerNotice(block: String,otherOwner: Boolean,ready: Boolean,dimensions: Pair<Int,Int>?,onTake: ()->Unit,modifier: Modifier=Modifier) {
    var visible by remember(block) {mutableStateOf(false)}
    LaunchedEffect(block,otherOwner,ready) {visible=false;if(otherOwner && ready) {delay(350);visible=true}}
    if(visible && otherOwner && ready) Row(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.96f)).padding(start=11.dp,end=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(stringResource(R.string.sized_by_another_client)+(dimensions?.let {" · ${it.first}×${it.second}"} ?: ""),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.take_over),Modifier.clickable(onClick=onTake).padding(vertical=9.dp),fontSize=10.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)
    }
}
@Composable internal fun OwnershipMenuItem(controlled: Boolean,enabled: Boolean,onClick: ()->Unit) {
    TerminalMenuItem(stringResource(if(controlled) R.string.release_control else R.string.take_control),ChromeIcon.POINTER,enabled=enabled,onClick=onClick)
}
@Composable internal fun ReadyNotice(epoch: Long,controlled: Boolean,ready: Boolean,modifier: Modifier=Modifier) {
    var shown by remember {mutableLongStateOf(0)};var visible by remember {mutableStateOf(false)}
    LaunchedEffect(epoch,controlled,ready) {
        if(epoch>shown && controlled && ready) {shown=epoch;visible=true;try {delay(1400)} finally {visible=false}}
    }
    if(visible) Text(stringResource(R.string.ready_to_type),modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal=12.dp,vertical=5.dp),
        fontSize=10.sp,color=MaterialTheme.colorScheme.onPrimaryContainer)
}
