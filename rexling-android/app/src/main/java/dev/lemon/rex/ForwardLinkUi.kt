package dev.lemon.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay

@Composable internal fun ForwardLinkUi(modifier: Modifier=Modifier,hazeState: HazeState?=null) {
    val context=LocalContext.current
    val message=PortForwards.linkNotice
    LaunchedEffect(message) {
        if(message?.autoOpen==true) {
            if((context as? ComponentActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true)
                PortForwards.openBrowser(context,message.url)
            if(PortForwards.linkNotice==message) PortForwards.linkNotice=message.copy(autoOpen=false)
        } else if(message!=null && (message.url.isNotBlank() || message.error.isNotBlank())) {
            delay(10_000)
            if(PortForwards.linkNotice==message) PortForwards.linkNotice=null
        }
    }
    if(message!=null) {
        val ready=message.url.isNotBlank() && message.error.isBlank()
        val record=PortForwards.items.firstOrNull {it.id==message.id}
        val ink=androidx.compose.ui.graphics.Color(0xff202a3c)
        val label=when {
            message.error.isNotBlank()->"Forwarding failed"
            ready->"Forwarding ${record?.target ?: message.url.substringAfter("://").substringBefore('/')}"
            PortForwards.conflict?.id==message.id->"Choose a phone port"
            else->"Starting forward…"
        }
        TransferNoticeSurface(modifier,hazeState,title="Port forwarding") {
            Column {
                Row(Modifier.padding(start=8.dp,end=2.dp).heightIn(min=32.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(!ready && message.error.isBlank() && PortForwards.conflict==null)
                        CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp)
                    else ChromeSymbol(ChromeIcon.RIGHT,Modifier.size(14.dp),color=MaterialTheme.colorScheme.primary)
                    Text(label,Modifier.weight(1f,fill=false).clip(RoundedCornerShape(4.dp))
                        .clickable(enabled=ready,role=Role.Button,onClick={PortForwards.openBrowser(context,message.url)})
                        .semantics {contentDescription=if(ready) "Open forwarded link" else "Forwarding status"},
                        style=UploadPillText,color=ink,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(ready) IconButton(onClick={
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Forwarded link",message.url))
                    },modifier=Modifier.size(32.dp).semantics {contentDescription="Copy forwarded link"}) {
                        ChromeSymbol(ChromeIcon.COPY,Modifier.size(13.dp),color=MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick={PortForwards.dismissLinkNotice()},modifier=Modifier.size(32.dp).semantics {contentDescription="Dismiss forwarding notice"}) {
                        ChromeSymbol(ChromeIcon.CLOSE,Modifier.size(13.dp),color=ink.copy(alpha=.6f))
                    }
                }
                if(message.error.isNotBlank()) Text(message.error,Modifier.padding(start=8.dp,end=8.dp,bottom=8.dp),style=SettingsType.detail,color=ink.copy(alpha=.75f))
            }
        }
    }
    val conflict=PortForwards.conflict
    val target=PortForwards.items.firstOrNull {it.id==conflict?.id}
    if(conflict!=null && target!=null) {
        val owner=PortForwards.items.firstOrNull {it.id==conflict.owner}
        AlertDialog(onDismissRequest={PortForwards.cancelConflict()},title={Text("Phone port ${target.localPort} is unavailable",style=SettingsType.title)},
            text={Text(if(owner!=null) "${owner.server} is forwarding ${owner.target} on this port. Choose another port, or stop that forward and reuse the port."
                else "This port is reserved or in use by another app. Rexling can choose an available phone port.",style=SettingsType.body)},
            confirmButton={Column(horizontalAlignment=Alignment.End) {
                TextButton(onClick={PortForwards.resolve(context,false)}) {Text("Choose another port")}
                if(owner!=null) TextButton(onClick={PortForwards.resolve(context,true)}) {Text("Stop other forward & use port")}
            }},dismissButton={TextButton(onClick={PortForwards.cancelConflict()}) {Text("Cancel")}})
    }
}
