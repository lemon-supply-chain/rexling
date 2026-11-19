package dev.lemon.rex

import androidx.compose.foundation.background
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
import kotlinx.coroutines.launch

@Composable internal fun EmptyServerContent(onAdd: ()->Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.welcome_title),style=SettingsType.title,color=MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.welcome_desc),style=SettingsType.body,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick=onAdd) {ChromeSymbol(ChromeIcon.ADD,Modifier.size(18.dp),color=LocalContentColor.current);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.add_server_title))}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ServersSheet(model: RexModel,hazeState: dev.chrisbanes.haze.HazeState?=null,onDismiss: ()->Unit) {
    var editing by remember {mutableStateOf<ServerProfile?>(null)}
    var editor by remember {mutableStateOf(false)}
    var scanning by remember {mutableStateOf(false)}
    // Built outside the sheet on purpose: the frost redraws when its host window draws, and inside the
    // sheet that host is the sheet's own window, which then redraws itself every frame while open.
    val frost=Modifier.drawerFrost(hazeState,BottomSheetDefaults.ExpandedShape)
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,tonalElevation=0.dp,dragHandle=null,contentWindowInsets={WindowInsets(0,0,0,0)}) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(frost.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                DrawerHandle()
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.servers),Modifier.weight(1f),style=SettingsType.row,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick={scanning=true}) {
                        ChromeSymbol(ChromeIcon.QR,color=MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(4.dp))
                    ChromeButton(ChromeIcon.ADD,stringResource(R.string.add_server_title)) {editing=null;editor=true}
                    ChromeButton(ChromeIcon.CLOSE,stringResource(R.string.close_servers),onClick=onDismiss)
                }
        Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=12.dp)) {
            Text(stringResource(R.string.count_connected_format,model.connectedCount),Modifier.padding(top=4.dp,bottom=8.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(model.servers.isEmpty()) Text(stringResource(R.string.add_rex_server_started),Modifier.padding(vertical=16.dp))
            for(server in model.servers) {
                val selected=server.id==model.activeServerId
                val status=model.serverStatuses[server.id] ?: ServerStatus()
                var menu by remember(server.id) {mutableStateOf(false)}
                Row(Modifier.fillMaxWidth().padding(bottom=4.dp).heightIn(min=56.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=if(selected) .8f else .35f))  // alpha-ok server row background
                    .clickable {onDismiss();model.connectServer(server.id)}.padding(start=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if(status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(11.dp))
                    ServerGlyph(server.icon,Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.label,style=SettingsType.row,color=MaterialTheme.colorScheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(server.endpoint,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(when(status.phase) {
                            "connected"->if(selected) stringResource(R.string.status_connected_viewing) else stringResource(R.string.status_connected)
                            "connecting"->stringResource(R.string.status_connecting)
                            "reconnecting"->stringResource(R.string.status_reconnecting)
                            "paused"->stringResource(R.string.status_paused)
                            "error"->stringResource(R.string.status_failed)
                            else->stringResource(R.string.status_disconnected)
                        },style=SettingsType.detail,color=if(status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if(status.error.isNotBlank()) Text(status.error,style=SettingsType.detail,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.error)
                    }
                    Box {
                        ChromeButton(ChromeIcon.MORE,stringResource(R.string.a11y_server_actions,server.label)) {menu=true}
                        DropdownMenu(menu,{menu=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                            TerminalMenuItem(stringResource(R.string.edit),ChromeIcon.EDIT,onClick={menu=false;editing=server;editor=true})
                            if(status.maintained) TerminalMenuItem(stringResource(R.string.disconnect),ChromeIcon.UNPLUG,onClick={menu=false;model.disconnectServer(server.id)})
                            TerminalMenuItem(stringResource(R.string.forget_server),ChromeIcon.DELETE,onClick={menu=false;model.forgetServer(server.id)})
                        }
                    }
                }
            }
            Text(stringResource(R.string.switch_servers_hint),Modifier.padding(top=9.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }
    }
    if(editor) ServerEditor(editing,onClose={editor=false},onSave={label,endpoint,icon->model.saveServer(editing?.id,label,endpoint,icon)})
    if(scanning) {
        QrScannerDialog(
            onDismiss={scanning=false},
            onScanned={label,endpoint->
                scanning=false
                model.saveServer(null,label,endpoint)
                model.connect(endpoint)
                onDismiss()
            }
        )
    }
}

@Composable internal fun ServerEditor(server: ServerProfile?,onClose: ()->Unit,onSave: (String,String,ServerIcon)->String?) {
    val networkStatus by NetworkSettings.status.collectAsState()
    val tailnetConnected=networkStatus.optJSONObject("tailscale")?.optString("BackendState")=="Running"
    var name by remember(server?.id) {mutableStateOf(server?.label.orEmpty())}
    var endpoint by remember(server?.id) {mutableStateOf(server?.endpoint.orEmpty())}
    var icon by remember(server?.id) {mutableStateOf(server?.icon ?: ServerIcon.GLOBE)}
    var error by remember {mutableStateOf<String?>(null)}
    var route by remember(server?.id) {mutableStateOf(NetworkSettings.route(server?.endpoint.orEmpty()))}
    var selectedKey by remember(server?.id) {mutableStateOf(NetworkSettings.selectedKey(server?.endpoint.orEmpty()))}
    var routesOpen by remember {mutableStateOf(false)}
    var networkOpen by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    if(networkOpen) NetworkSheet(onClose={networkOpen=false})
    AlertDialog(onDismissRequest={if(!busy) onClose()},title={Text(if(server==null) stringResource(R.string.add_server_title) else stringResource(R.string.edit_server_title),style=SettingsType.title)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                ServerIconPicker(icon,compact=true) {icon=it}
                OutlinedTextField(name,{name=it;error=null},Modifier.weight(1f),label={Text(stringResource(R.string.name))},singleLine=true,textStyle=SettingsType.body)
            }
            OutlinedTextField(endpoint,{endpoint=it;error=null},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.server_url))},singleLine=true,textStyle=SettingsType.body,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),placeholder={Text("https://host.tailnet.ts.net",fontSize=11.sp)})
            Text(if(endpoint.startsWith("ssh://")) stringResource(R.string.ssh_target_hint) else stringResource(R.string.https_target_hint),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(runCatching {ServerProfile.normalizeEndpoint(endpoint)}.isSuccess) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f)).padding(12.dp)) {  // alpha-ok network route background
                Text(stringResource(R.string.connection_network),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    TextButton(enabled=!busy,onClick={routesOpen=true},contentPadding=PaddingValues(horizontal=0.dp,vertical=4.dp)) {Text(stringResource(route.labelRes),style=SettingsType.row);Text(" ▾")}
                    DropdownMenu(expanded=routesOpen,onDismissRequest={routesOpen=false}) {
                        ServerNetworkRoute.entries.forEach {choice->DropdownMenuItem(text={Text(stringResource(choice.labelRes),style=SettingsType.row)},onClick={route=choice;routesOpen=false})}
                    }
                }
                if(route==ServerNetworkRoute.BUILTIN) {
                    Text(stringResource(R.string.tailnet_route_desc),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick={networkOpen=true},contentPadding=PaddingValues(horizontal=0.dp,vertical=4.dp)) {Text(if(tailnetConnected) stringResource(R.string.status_connected) else stringResource(R.string.setup_or_signin),style=SettingsType.detail)}
                } else Text(stringResource(R.string.system_route_desc),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(endpoint.startsWith("ssh://")) {
                val normalized=runCatching {ServerProfile.normalizeEndpoint(endpoint)}.getOrNull()
                if(normalized!=null) SshKeyEditor(selectedKey) {selectedKey=it}
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
        }
    },confirmButton={Button(onClick={
        val profile=runCatching {ServerProfile.create(name,endpoint,icon=icon)}.getOrElse {error=it.message;return@Button}
        val requestedRoute=route
        val requestedKey=selectedKey
        busy=true
        scope.launch {
            val previous=NetworkSettings.route(profile.endpoint)
            val previousKey=NetworkSettings.selectedKey(profile.endpoint)
            val ssh=profile.endpoint.startsWith("ssh://")
            error=runCatching {
                NetworkSettings.saveRoute(profile.endpoint,requestedRoute) ?:
                    (if(ssh) NetworkSettings.assignKey(profile.endpoint,requestedKey) else null) ?:
                    onSave(profile.label,profile.endpoint,profile.icon)
            }.getOrElse {it.message ?: "Could not save server"}
            if(error!=null) {
                if(previous!=requestedRoute) NetworkSettings.saveRoute(profile.endpoint,previous)
                if(ssh && previousKey!=NetworkSettings.selectedKey(profile.endpoint)) NetworkSettings.assignKey(profile.endpoint,previousKey)
            }
            busy=false;if(error==null) onClose()
        }
    },enabled=endpoint.isNotBlank() && !busy) {Text(if(busy) stringResource(R.string.saving) else stringResource(R.string.save_and_connect))}},
        dismissButton={TextButton(enabled=!busy,onClick=onClose) {Text(stringResource(R.string.cancel))}})
}
