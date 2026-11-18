package dev.lemon.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PortForwardSheet(servers: List<ForwardServer>,selectedServer: String,hazeState: HazeState?,onClose: ()->Unit,onStart: ((String)->Unit)?=null,
    onAdd: (ForwardServer,String,Int,Int)->Unit) {
    val context=LocalContext.current
    var editing by remember {mutableStateOf(false)}
    val canAdd=servers.any {it.connected && it.session.isNotBlank()} && PortForwards.items.size<64
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,tonalElevation=0.dp,dragHandle=null,contentWindowInsets={WindowInsets(0,0,0,0)}) {
        Column(Modifier.drawerFrost(hazeState,BottomSheetDefaults.ExpandedShape).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(bottom=4.dp)) {
            DrawerHandle()
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.port_forwarding),Modifier.weight(1f),style=SettingsType.row,fontWeight=FontWeight.Bold)
                ChromeButton(ChromeIcon.ADD,stringResource(R.string.add_port_forward),enabled=canAdd) {editing=true}
                ChromeButton(ChromeIcon.CLOSE,stringResource(R.string.close_port_forwarding),onClick=onClose)
            }
            Text(stringResource(R.string.running_forwards_format,PortForwards.items.count {it.running}),Modifier.padding(horizontal=16.dp,vertical=6.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            PortForwards.notice?.let {notice->
                Text(notice,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.error)
                TextButton(onClick={PortForwards.notice=null}) {Text(stringResource(R.string.dismiss))}
            }
            if(PortForwards.items.isEmpty()) {
                Text(stringResource(R.string.tap_localhost_hint),Modifier.padding(16.dp),style=SettingsType.detail)
                TextButton(onClick={editing=true},enabled=canAdd,modifier=Modifier.padding(horizontal=8.dp)) {Text(stringResource(R.string.add_port_forward))}
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=440.dp),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                PortForwards.items.groupBy {it.serverId.ifBlank {it.endpoint}}.forEach {(serverId,entries)->
                    item(key="server:$serverId") {
                        val profile=servers.firstOrNull {it.profile.id==serverId}?.profile
                        Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            ServerGlyph(profile?.icon ?: ServerIcon.GLOBE,Modifier.size(16.dp))
                            Text(profile?.label ?: entries.first().server,style=SettingsType.section,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(entries,key={it.id}) {item->
                        val actionDesc=stringResource(if(item.running) R.string.stop_forward else R.string.start_forward)
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh,RoundedCornerShape(12.dp)).padding(start=4.dp,end=0.dp,top=4.dp,bottom=4.dp)) {
                            Row(Modifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                                val stateColor=when(item.phase) {
                                    "active"->MaterialTheme.colorScheme.primary
                                    "connecting","reconnecting"->Color(0xFFD69B42)
                                    "failed"->MaterialTheme.colorScheme.error
                                    else->MaterialTheme.colorScheme.outline
                                }
                                Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) {
                                    IconButton(onClick={
                                        if(item.running) PortForwards.stop(context,item.id) else (onStart ?: {id: String->PortForwards.requestStart(context,id)})(item.id)
                                    },modifier=Modifier.fillMaxSize().semantics {contentDescription=actionDesc}) {
                                        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha=.09f)),contentAlignment=Alignment.Center) {  // alpha-ok decorative tint
                                            ChromeSymbol(if(item.running) ChromeIcon.STOP else ChromeIcon.PLAY,Modifier.size(19.dp),color=MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.size(6.dp).background(stateColor,CircleShape).semantics {contentDescription=context.getString(R.string.a11y_forward_phase,item.phase)})
                                Spacer(Modifier.width(6.dp))
                                Text("${item.boundPort.takeIf {it>0} ?: "auto"}",style=SettingsType.row.copy(fontFamily=MonoUi),color=MaterialTheme.colorScheme.onSurface)
                                ChromeSymbol(ChromeIcon.RIGHT,Modifier.padding(horizontal=4.dp).size(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.target,Modifier.weight(1f),style=SettingsType.row.copy(fontFamily=MonoUi),color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick={PortForwards.remove(context,item.id)},modifier=Modifier.size(48.dp).semantics {contentDescription=context.getString(R.string.a11y_remove_forward,item.target)}) {
                                    ChromeSymbol(ChromeIcon.CLOSE,Modifier.size(18.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if(item.error.isNotBlank()) Text(item.error,Modifier.padding(start=68.dp,bottom=4.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.error)
                            if(item.address.isNotBlank() && item.phase=="active") Row(Modifier.padding(start=68.dp).height(32.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(item.url,Modifier.weight(1f),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                TextButton(onClick={PortForwards.openBrowser(context,item.url)},modifier=Modifier.height(32.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)) {Text(stringResource(R.string.open_link),style=SettingsType.detail)}
                                IconButton(onClick={
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Forwarded URL",item.url))
                                },modifier=Modifier.width(48.dp).height(32.dp).semantics {contentDescription=context.getString(R.string.a11y_copy_forwarded_url)}) {
                                    ChromeSymbol(ChromeIcon.COPY,Modifier.size(19.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if(editing) ForwardEditor(servers,selectedServer,onClose={editing=false}) {target,host,port,local->editing=false;onAdd(target,host,port,local)}
}
@Composable private fun ForwardEditor(servers: List<ForwardServer>,selectedServer: String,onClose: ()->Unit,onSave: (ForwardServer,String,Int,Int)->Unit) {
    var serverId by remember {mutableStateOf(servers.firstOrNull {it.profile.id==selectedServer && it.connected && it.session.isNotBlank()}?.profile?.id ?: servers.firstOrNull {it.connected && it.session.isNotBlank()}?.profile?.id)}
    var serverMenu by remember {mutableStateOf(false)}
    val server=servers.firstOrNull {it.profile.id==serverId}
    var host by remember {mutableStateOf("127.0.0.1")}
    var port by remember {mutableStateOf("")}
    var local by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    var advanced by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=onClose,title={Text(stringResource(R.string.add_port_forward),style=SettingsType.title)},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Box {
                TextButton(onClick={serverMenu=true}) {server?.let {ServerGlyph(it.profile.icon,Modifier.size(18.dp));Spacer(Modifier.width(8.dp))};Text("${server?.profile?.label ?: stringResource(R.string.choose_server)} ▾")}
                DropdownMenu(serverMenu,{serverMenu=false}) {
                    servers.forEach {target->DropdownMenuItem(text={Text(target.profile.label+if(!target.connected || target.session.isBlank()) " · connect first" else "")},
                        leadingIcon={ServerGlyph(target.profile.icon,Modifier.size(19.dp))},enabled=target.connected && target.session.isNotBlank(),onClick={serverId=target.profile.id;serverMenu=false})}
                }
            }
            OutlinedTextField(port,{port=it;error=null},label={Text(stringResource(R.string.remote_port))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            TextButton(onClick={advanced=!advanced},contentPadding=PaddingValues(horizontal=0.dp)) {Text(if(advanced) stringResource(R.string.hide_advanced) else stringResource(R.string.advanced))}
            if(advanced) {
                OutlinedTextField(host,{host=it;error=null},label={Text(stringResource(R.string.remote_host))},singleLine=true)
                OutlinedTextField(local,{local=it;error=null},label={Text(stringResource(R.string.phone_port))},placeholder={Text(stringResource(R.string.same_as_remote))},supportingText={Text(stringResource(R.string.phone_port_zero_hint),style=SettingsType.detail)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            }
            error?.let {Text(it,style=SettingsType.detail,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(onClick={
        val remote=port.toIntOrNull();val localPort=if(local.isBlank()) remote else local.toIntOrNull()
        val name=host.trim().removeSurrounding("[","]")
        if(remote==null || remote !in 1..65535 || localPort==null || localPort !in 0..65535) error="Enter valid ports from 1 to 65535."
        else if(name.isBlank() || name.length>253 || name.any {it.isWhitespace() || it=='/' || it.isISOControl()}) error="Enter a hostname or IP address."
        else if(server==null || !server.connected || server.session.isBlank()) error="Connect a server first."
        else onSave(server,name,remote,localPort)
    }) {Text(stringResource(R.string.forward_action))}},dismissButton={TextButton(onClick=onClose) {Text(stringResource(R.string.cancel))}})
}
