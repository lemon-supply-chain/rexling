package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ServersSheet(model: RexModel,onDismiss: ()->Unit) {
    var editing by remember {mutableStateOf<ServerProfile?>(null)}
    var editor by remember {mutableStateOf(false)}
    var scanning by remember {mutableStateOf(false)}
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(horizontal=18.dp).padding(bottom=25.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom=14.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.servers),fontSize=20.sp,fontWeight=FontWeight.Bold)
                    Text(stringResource(R.string.count_connected_format,model.connectedCount),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick={scanning=true}) {
                    ChromeSymbol(ChromeIcon.QR,color=MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick={editing=null;editor=true}) {Text(stringResource(R.string.add_server))}
            }
            if(model.servers.isEmpty()) Text(stringResource(R.string.add_rex_server_started),Modifier.padding(vertical=16.dp))
            for(server in model.servers) {
                val selected=server.id==model.activeServerId
                val status=model.serverStatuses[server.id] ?: ServerStatus()
                var menu by remember(server.id) {mutableStateOf(false)}
                Row(Modifier.fillMaxWidth().padding(bottom=8.dp).clip(RoundedCornerShape(16.dp))
                    .background(if(selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .border(1.dp,if(selected) MaterialTheme.colorScheme.primary.copy(alpha=.25f) else Color.Transparent,RoundedCornerShape(16.dp))
                    .clickable {onDismiss();model.connectServer(server.id)}.padding(start=13.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if(status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(11.dp))
                    ServerGlyph(server.icon,Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.label,fontWeight=FontWeight.SemiBold,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(server.endpoint,fontFamily=MonoUi,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(when(status.phase) {
                            "connected"->if(selected) stringResource(R.string.status_connected_viewing) else stringResource(R.string.status_connected)
                            "connecting"->stringResource(R.string.status_connecting)
                            "reconnecting"->stringResource(R.string.status_reconnecting)
                            "paused"->stringResource(R.string.status_paused)
                            "error"->stringResource(R.string.status_failed)
                            else->stringResource(R.string.status_disconnected)
                        },fontSize=11.sp,color=if(status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if(status.error.isNotBlank()) Text(status.error,fontSize=10.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.error)
                    }
                    Box {
                        ChromeButton(ChromeIcon.MORE,"Server actions for ${server.label}") {menu=true}
                        DropdownMenu(menu,{menu=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                            TerminalMenuItem(stringResource(R.string.edit),ChromeIcon.EDIT,onClick={menu=false;editing=server;editor=true})
                            if(status.maintained) TerminalMenuItem(stringResource(R.string.disconnect),ChromeIcon.UNPLUG,onClick={menu=false;model.disconnectServer(server.id)})
                            TerminalMenuItem(stringResource(R.string.forget_server),ChromeIcon.DELETE,onClick={menu=false;model.forgetServer(server.id)})
                        }
                    }
                }
            }
            Text(stringResource(R.string.switch_servers_hint),Modifier.padding(top=9.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(scanning) {
        QrScannerDialog(onDismiss={scanning=false},onScanned={label,endpoint->
            val err=model.saveServer(null,label,endpoint)
            if(err==null) onDismiss() else {
                editing=model.servers.firstOrNull {it.endpoint==endpoint}
                editor=true
            }
        })
    }
    if(editor) ServerEditor(editing,onClose={editor=false},onSave={label,endpoint,icon->model.saveServer(editing?.id,label,endpoint,icon)})
}

@Composable internal fun ServerEditor(server: ServerProfile?,onClose: ()->Unit,onSave: (String,String,ServerIcon)->String?) {
    var name by remember(server?.id) {mutableStateOf(server?.label.orEmpty())}
    var endpoint by remember(server?.id) {mutableStateOf(server?.endpoint.orEmpty())}
    var icon by remember(server?.id) {mutableStateOf(server?.icon ?: ServerIcon.GLOBE)}
    var error by remember {mutableStateOf<String?>(null)}
    var scanning by remember {mutableStateOf(false)}
    if(scanning) {
        QrScannerDialog(onDismiss={scanning=false},onScanned={label,url->
            name=label;endpoint=url;scanning=false
        })
    }
    AlertDialog(onDismissRequest=onClose,title={Text(stringResource(if(server==null) R.string.add_server_title else R.string.edit_server_title))},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            ServerIconPicker(icon) {icon=it}
            OutlinedTextField(name,{name=it;error=null},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.name))},singleLine=true)
            OutlinedTextField(endpoint,{endpoint=it;error=null},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.server_url))},singleLine=true,
                trailingIcon={
                    IconButton(onClick={scanning=true}) {
                        ChromeSymbol(ChromeIcon.QR,color=MaterialTheme.colorScheme.primary)
                    }
                },
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),placeholder={Text("https://host.tailnet.ts.net",fontSize=11.sp)})
            Text(stringResource(R.string.tailscale_address_hint),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
        }
    },confirmButton={Button(onClick={error=onSave(name,endpoint,icon);if(error==null) onClose()},enabled=endpoint.isNotBlank()) {Text(stringResource(R.string.save_and_connect))}},
        dismissButton={TextButton(onClick=onClose) {Text(stringResource(R.string.cancel))}})
}
