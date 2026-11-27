package dev.lemon.rex

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun EmptyServerContent(onAdd: () -> Unit, onPreview: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.welcome_title),
            style = SettingsType.title,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.welcome_desc),
            style = SettingsType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onAdd) {
                ChromeSymbol(
                    ChromeIcon.ADD,
                    Modifier.size(18.dp),
                    color = LocalContentColor.current,
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_server_title))
            }
            if (onPreview != null) {
                OutlinedButton(onClick = onPreview) {
                    Text(stringResource(R.string.status_preview))
                }
            }
        }
    }
}

@Composable
internal fun ServersSheet(
    model: RexModel,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    var editor by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var revealedServer by remember { mutableStateOf<String?>(null) }
    val scroll = rememberLazyListState()
    val subtitle =
        if (model.servers.size > 1)
            stringResource(
                R.string.servers_connected_switching_summary_format,
                model.connectedCount,
                model.servers.size,
            )
        else
            stringResource(
                R.string.servers_connected_summary_format,
                model.connectedCount,
                model.servers.size,
            )
    val activity = model.activitySources.associateBy { it.server.id }
    if (!editor)
        SettingsDrawer(
            stringResource(R.string.servers),
            stringResource(R.string.close_servers),
            onDismiss,
            hazeState,
            subtitle = subtitle,
            headerActions = {
                IconButton(
                    onClick = {
                        revealedServer = null
                        scanning = true
                    },
                ) {
                    ChromeSymbol(ChromeIcon.QR, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(4.dp))
                ChromeButton(ChromeIcon.ADD, stringResource(R.string.add_server_title)) {
                    revealedServer = null
                    editing = null
                    editor = true
                }
            },
        ) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 560.dp),
                state = scroll,
                contentPadding =
                    PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (model.servers.isEmpty())
                    item {
                        Text(
                            stringResource(R.string.add_rex_server_started),
                            Modifier.padding(vertical = 12.dp),
                            style = SettingsType.paneDetail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                items(model.servers, key = { it.id }) { server ->
                    val source = activity[server.id]?.takeIf { it.connected }
                    ServerDrawerRow(
                        server,
                        model.serverStatuses[server.id] ?: ServerStatus(),
                        server.id == model.activeServerId,
                        unread =
                            source?.items?.any { it.notifiable && it.key !in source.seen } == true,
                        working = source?.items?.any { it.state == ActivityState.WORKING } == true,
                        revealed = revealedServer == server.id,
                        onReveal = { open ->
                            if (open) revealedServer = server.id
                            else if (revealedServer == server.id) revealedServer = null
                        },
                        onSelect = {
                            onDismiss()
                            model.connectServer(server.id)
                        },
                        onEdit = {
                            editing = server
                            editor = true
                        },
                        onDisconnect = { model.disconnectServer(server.id) },
                        onRemove = { model.forgetServer(server.id) },
                    )
                }
            }
        }
    if (editor) {
        val server = editing
        ServerEditor(
            server,
            onClose = { editor = false },
            status = model.serverStatuses[server?.id] ?: ServerStatus(),
            onRemove = server?.let { profile -> { model.forgetServer(profile.id) } },
            onSave = { label, endpoint, icon ->
                model.saveServer(server?.id, label, endpoint, icon)
            },
        )
    }
    if (scanning) {
        QrScannerDialog(
            onDismiss = { scanning = false },
            onScanned = { label, endpoint ->
                scanning = false
                model.saveServer(null, label, endpoint)
                model.connect(endpoint)
                onDismiss()
            },
        )
    }
}

@Composable
internal fun ServerDrawerRow(
    server: ServerProfile,
    status: ServerStatus,
    selected: Boolean,
    unread: Boolean,
    working: Boolean,
    revealed: Boolean,
    onReveal: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmation by remember(server.id) { mutableStateOf<ServerConfirmation?>(null) }
    val editLabel = stringResource(R.string.edit_server_action)
    val disconnectLabel = stringResource(R.string.disconnect_server_action)
    val removeLabel = stringResource(R.string.remove_server_action)
    val actions = buildList {
        add(SwitcherRowAction(editLabel, ChromeIcon.EDIT, run = onEdit))
        if (status.maintained)
            add(
                SwitcherRowAction(disconnectLabel, ChromeIcon.UNPLUG) {
                    confirmation = ServerConfirmation.DISCONNECT
                },
            )
        add(
            SwitcherRowAction(removeLabel, ChromeIcon.CLOSE, destructive = true) {
                confirmation = ServerConfirmation.REMOVE
            },
        )
    }
    val colors = MaterialTheme.colorScheme
    val connecting = status.phase in listOf("connecting", "reconnecting")
    val failed = status.phase == "error"
    val inactive = !status.connected && !connecting && !failed
    val connection = phaseLabel(status.phase)
    val accessibilityStatus =
        listOfNotNull(
                connection,
                stringResource(R.string.viewing).takeIf { selected },
                stringResource(R.string.a11y_state_working).takeIf { working },
                stringResource(R.string.a11y_server_state_unread).takeIf { unread },
            )
            .joinToString(", ")
    val serverContentDescription = stringResource(R.string.a11y_server_label, server.label)
    val labelColor = if (inactive && !selected) colors.onSurfaceVariant else colors.onSurface
    // alpha-ok row background composited over sheet surface
    val rowBackground =
        colors.surfaceVariant
            .copy(alpha = if (selected) .8f else .35f)
            .compositeOver(colors.surface)
    val dark = LocalTerminalPalette.current.dark
    val connectionColor =
        when {
            status.connected -> if (dark) Color(0xff73cd87) else Color(0xff217638)
            connecting -> if (dark) Color(0xffedbc74) else Color(0xff955a0c)
            failed -> colors.error
            else -> colors.outline
        }
    SwitcherSwipeRow(revealed, onReveal, actions, enabled = true) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 64.dp)
                .background(rowBackground)
                .selectable(
                    selected,
                    role = Role.RadioButton,
                    onClick = { if (revealed) onReveal(false) else onSelect() },
                )
                .semantics {
                    contentDescription = serverContentDescription
                    stateDescription = accessibilityStatus
                    customActions = actions.map { action ->
                        CustomAccessibilityAction(action.label) {
                            onReveal(false)
                            action.run()
                            true
                        }
                    }
                }
                .padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.width(3.dp)
                    .height(22.dp)
                    .then(
                        if (selected) Modifier.background(colors.primary, RoundedCornerShape(2.dp))
                        else Modifier,
                    ),
            )
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(22.dp)) {
                    ServerGlyph(
                        server.icon,
                        Modifier.matchParentSize(),
                        if (selected || working) colors.primary else labelColor,
                    )
                    if (unread)
                        ServerIconDot(
                            colors.primary,
                            rowBackground,
                            Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
                        )
                    ServerIconDot(
                        connectionColor,
                        rowBackground,
                        Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        server.label,
                        Modifier.weight(1f),
                        style = SettingsType.paneTitle,
                        color = if (selected) colors.primary else labelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        status.version.takeIf { status.connected && it.isNotBlank() } ?: connection,
                        Modifier.widthIn(max = 118.dp),
                        style = SettingsType.paneDetail,
                        color = if (failed) colors.error else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    server.endpoint,
                    style = SettingsType.paneDetail,
                    fontFamily = MonoUi,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (failed && status.error.isNotBlank()) {
                    Text(
                        status.error,
                        style = SettingsType.paneDetail,
                        color = colors.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    confirmation?.let { action ->
        ServerConfirmationDialog(
            server,
            action,
            onCancel = { confirmation = null },
            onConfirm = {
                confirmation = null
                when (action) {
                    ServerConfirmation.DISCONNECT -> onDisconnect()
                    ServerConfirmation.REMOVE -> onRemove()
                }
            },
        )
    }
}

@Composable
private fun ServerIconDot(color: Color, background: Color, modifier: Modifier) {
    Box(
        modifier.size(10.dp).border(1.5.dp, background, CircleShape).background(color, CircleShape),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerEditor(
    server: ServerProfile?,
    onClose: () -> Unit,
    status: ServerStatus = ServerStatus(),
    onRemove: (() -> Unit)? = null,
    onSave: (String, String, ServerIcon) -> String?,
) {
    val networkStatus by NetworkSettings.status.collectAsStateWithLifecycle()
    val tailnetConnected =
        networkStatus.optJSONObject("tailscale")?.optString("BackendState") == "Running"
    val savedRoute =
        remember(server?.endpoint) { NetworkSettings.route(server?.endpoint.orEmpty()) }
    val savedKey =
        remember(server?.endpoint) { NetworkSettings.selectedKey(server?.endpoint.orEmpty()) }
    var name by remember(server?.id) { mutableStateOf(server?.label.orEmpty()) }
    var endpoint by remember(server?.id) { mutableStateOf(server?.endpoint.orEmpty()) }
    var icon by remember(server?.id) { mutableStateOf(server?.icon ?: ServerIcon.GLOBE) }
    var error by remember { mutableStateOf<String?>(null) }
    var route by remember(server?.id) { mutableStateOf(savedRoute) }
    var selectedKey by remember(server?.id) { mutableStateOf(savedKey) }
    var routesOpen by remember { mutableStateOf(false) }
    var networkOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val saveErrorFallback = stringResource(R.string.could_not_save_server)
    val normalized =
        remember(endpoint) { runCatching { ServerProfile.normalizeEndpoint(endpoint) }.getOrNull() }
    val ssh = normalized?.startsWith("ssh://") == true
    val reachable =
        status.connected &&
            normalized != null &&
            normalized == server?.endpoint &&
            route == savedRoute &&
            (!ssh || selectedKey == savedKey)

    val checkAllowed = normalized != null && !busy && !networkOpen
    val checkingKey = selectedKey.takeIf { ssh }
    var urlCheck by
        remember(normalized, route, checkingKey, checkAllowed, tailnetConnected) {
            mutableStateOf<ServerUrlCheck>(ServerUrlCheck.Editing)
        }
    LaunchedEffect(normalized, route, checkingKey, checkAllowed, tailnetConnected, reachable) {
        if (normalized == null || !checkAllowed || reachable) return@LaunchedEffect
        urlCheck = ServerUrlCheck.Editing
        delay(700)
        if (route == ServerNetworkRoute.BUILTIN && !tailnetConnected) {
            urlCheck = ServerUrlCheck.Error(R.string.server_check_tailscale_first)
        } else {
            urlCheck = ServerUrlCheck.Checking
            urlCheck = NetworkSettings.checkServer(normalized, route, checkingKey)
        }
    }
    val urlState = if (reachable) ServerUrlCheck.Reachable(status.version) else urlCheck

    fun save() {
        if (busy) return
        val profile = runCatching {
            ServerProfile.create(name, endpoint, icon = icon)
        }
            .getOrElse {
                error = it.message
                return
            }
        val requestedRoute = route
        val requestedKey = selectedKey
        busy = true
        error = null
        scope.launch {
            val previousRoute = NetworkSettings.route(profile.endpoint)
            val previousKey = NetworkSettings.selectedKey(profile.endpoint)
            var committed = false
            try {
                error =
                    NetworkSettings.saveRoute(profile.endpoint, requestedRoute)
                        ?: (if (ssh) NetworkSettings.assignKey(profile.endpoint, requestedKey)
                        else null)
                        ?: onSave(profile.label, profile.endpoint, profile.icon)
                committed = error == null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: saveErrorFallback
            } finally {
                if (!committed)
                    withContext(NonCancellable) {
                        if (previousRoute != requestedRoute)
                            NetworkSettings.saveRoute(profile.endpoint, previousRoute)
                        if (ssh && previousKey != requestedKey)
                            NetworkSettings.assignKey(profile.endpoint, previousKey)
                    }
                busy = false
            }
            if (committed) onClose()
        }
    }

    if (networkOpen) {
        NetworkSheet(onClose = { networkOpen = false })
        return
    }

    SettingsDrawer(
        if (server == null) stringResource(R.string.add_server_title)
        else stringResource(R.string.edit_server_title),
        stringResource(R.string.close_server_editor),
        onClose,
        onBack = onClose.takeIf { server != null },
        backDescription = stringResource(R.string.back_to_servers),
        surfaceColor = colors.surface,
        showClose = server == null,
        dismissEnabled = !busy,
        headerActions = {
            Button(
                ::save,
                enabled = endpoint.isNotBlank() && !busy,
                modifier = Modifier.padding(end = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    if (busy) stringResource(R.string.saving)
                    else if (server == null) stringResource(R.string.save_and_connect)
                    else stringResource(R.string.save),
                    style = SettingsType.row,
                )
            }
        },
    ) {
        // alpha-ok subtle divider line
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .55f))
        Column(
            Modifier.fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 560.dp)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = if (server == null) 28.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ServerIconPicker(icon, compact = true, enabled = !busy) { icon = it }
                OutlinedTextField(
                    name,
                    {
                        name = it
                        error = null
                    },
                    Modifier.weight(1f),
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = SettingsType.body,
                    shape = shape,
                )
            }
            OutlinedTextField(
                endpoint,
                {
                    endpoint = it
                    error = null
                },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.server_url)) },
                singleLine = true,
                enabled = !busy,
                isError = urlState is ServerUrlCheck.Error,
                textStyle = SettingsType.body.copy(fontFamily = MonoUi),
                shape = shape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                placeholder = {
                    Text(
                        "https://host.tailnet.ts.net",
                        style = SettingsType.detail,
                        fontFamily = MonoUi,
                    )
                },
                supportingText = { ServerUrlStatus(urlState) },
            )
            if (normalized != null) {
                Column {
                    ExposedDropdownMenuBox(routesOpen, { if (!busy) routesOpen = it }) {
                        OutlinedTextField(
                            stringResource(route.labelRes),
                            {},
                            Modifier.fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = !busy),
                            readOnly = true,
                            enabled = !busy,
                            singleLine = true,
                            shape = shape,
                            textStyle = SettingsType.body,
                            label = { Text(stringResource(R.string.connection_network)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(routesOpen) },
                            supportingText = {
                                Text(
                                    if (route == ServerNetworkRoute.BUILTIN)
                                        stringResource(R.string.tailnet_route_short_desc)
                                    else stringResource(R.string.system_route_desc),
                                    style = SettingsType.detail,
                                )
                            },
                        )
                        ExposedDropdownMenu(routesOpen, { routesOpen = false }) {
                            ServerNetworkRoute.entries.forEach { choice ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(choice.labelRes),
                                            style = SettingsType.row,
                                        )
                                    },
                                    onClick = {
                                        route = choice
                                        routesOpen = false
                                        error = null
                                    },
                                )
                            }
                        }
                    }
                    if (route == ServerNetworkRoute.BUILTIN)
                        TextButton(
                            onClick = { networkOpen = true },
                            enabled = !busy,
                            modifier = Modifier.padding(start = 6.dp),
                        ) {
                            Text(
                                if (tailnetConnected) stringResource(R.string.status_connected)
                                else stringResource(R.string.setup_or_signin),
                                style = SettingsType.detail,
                            )
                        }
                }
                if (ssh) SshKeyEditor(selectedKey, enabled = !busy) { selectedKey = it }
            }
            error?.let { Text(it, color = colors.error, style = SettingsType.detail) }
            if (onRemove != null) {
                // alpha-ok subtle divider line
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .55f))
                TextButton(
                    onClick = { confirmRemove = true },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                ) {
                    ChromeSymbol(ChromeIcon.DELETE, Modifier.size(18.dp), LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.remove_server_action), style = SettingsType.row)
                }
            }
        }
    }
    if (confirmRemove && onRemove != null && server != null) {
        ServerConfirmationDialog(
            server,
            ServerConfirmation.REMOVE,
            onCancel = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                onRemove()
                onClose()
            },
        )
    }
}

@Composable
private fun ServerUrlStatus(status: ServerUrlCheck) {
    val colors = MaterialTheme.colorScheme
    val green = if (LocalTerminalPalette.current.dark) Color(0xff73cd87) else Color(0xff217638)
    when (status) {
        ServerUrlCheck.Editing ->
            Text(stringResource(R.string.server_url_supporting_text), style = SettingsType.detail)
        else ->
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (status) {
                    ServerUrlCheck.Checking -> {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.checking),
                            style = SettingsType.detail,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    is ServerUrlCheck.Reachable -> {
                        Box(
                            Modifier.size(16.dp).border(1.dp, green, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            ChromeSymbol(ChromeIcon.CHECK, Modifier.size(10.dp), green)
                        }
                        val reachableText = stringResource(R.string.reachable)
                        val versionText =
                            if (status.version.isNotBlank())
                                stringResource(R.string.rex_version_format, status.version)
                            else null
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = green)) { append(reachableText) }
                                if (versionText != null)
                                    withStyle(SpanStyle(color = colors.onSurfaceVariant)) {
                                        append(" · $versionText")
                                    }
                            },
                            style = SettingsType.detail,
                        )
                    }
                    is ServerUrlCheck.Error -> {
                        Box(
                            Modifier.size(14.dp).border(1.dp, colors.error, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("!", style = SettingsType.paneDetail, color = colors.error)
                        }
                        Text(
                            stringResource(
                                R.string.cant_reach_server_format,
                                stringResource(status.reasonRes),
                            ),
                            style = SettingsType.detail,
                            color = colors.error,
                        )
                    }
                    ServerUrlCheck.Editing -> Unit
                }
            }
    }
}

private enum class ServerConfirmation(
    @param:StringRes val actionRes: Int,
    @param:StringRes val titleRes: Int,
    @param:StringRes val messageRes: Int,
) {
    DISCONNECT(
        R.string.disconnect,
        R.string.disconnect_server_confirm_title,
        R.string.disconnect_server_confirm_message,
    ),
    REMOVE(
        R.string.remove_action,
        R.string.remove_server_confirm_title,
        R.string.remove_server_confirm_message,
    ),
}

@Composable
private fun ServerConfirmationDialog(
    server: ServerProfile,
    action: ServerConfirmation,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(action.titleRes)) },
        text = { Text(stringResource(action.messageRes, server.label)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(action.actionRes),
                    color =
                        if (action == ServerConfirmation.REMOVE) MaterialTheme.colorScheme.error
                        else LocalContentColor.current,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}
