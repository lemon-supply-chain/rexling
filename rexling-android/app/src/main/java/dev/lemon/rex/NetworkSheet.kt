package dev.lemon.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkSheet(
    server: ServerProfile? = null,
    serverConnected: Boolean = false,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by NetworkSettings.status.collectAsStateWithLifecycle()
    var control by remember { mutableStateOf(NetworkSettings.controlUrl) }
    var deviceName by remember { mutableStateOf(NetworkSettings.hostname) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val tailscale = status.optJSONObject("tailscale")
    val auth = tailscale?.optString("AuthURL").orEmpty()
    fun openLogin(url: String) {
        val uri = Uri.parse(url)
        require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrEmpty()) {
            "Tailscale returned an invalid sign-in address"
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
    fun signIn() {
        busy = true
        error = null
        scope.launch {
            try {
                error = NetworkSettings.prepareBuiltIn(control, deviceName)
                if (error == null) error = NetworkSettings.login()
                if (error == null) {
                    val url =
                        withTimeoutOrNull(20_000) {
                            awaitTailscaleAuth {
                                NetworkSettings.refreshStatus()
                                NetworkSettings.status.value
                            }
                        }
                    if (url == null)
                        error =
                            "Sign-in did not return a browser link. Check your connection and try again."
                    else if (url.isNotEmpty()) openLogin(url)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not start Tailscale sign-in"
            } finally {
                busy = false
            }
        }
    }
    SettingsDrawer(
        stringResource(R.string.network),
        stringResource(R.string.close_network_settings),
        onClose,
        hazeState,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (server != null && server.endpoint.isNotBlank())
                ServerNetworkStatusCard(
                    server.label,
                    NetworkSettings.route(server.endpoint),
                    serverConnected,
                )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TailscaleStatusCard(
                    if (!NetworkSettings.tailscaleEnabled) "Off"
                    else tailscale?.optString("BackendState").orEmpty(),
                    busy,
                    peers = tailscalePeers(tailscale),
                    networkError =
                        if (NetworkSettings.tailscaleEnabled) status.optString("error") else "",
                    onSignIn = ::signIn,
                    onSignOut = { confirmSignOut = true },
                )
                if (
                    NetworkSettings.tailscaleEnabled &&
                        auth.isNotBlank() &&
                        tailscale?.optString("BackendState") != "Running"
                )
                    PendingTailscaleSignIn(
                        busy,
                        onContinue = {
                            runCatching { openLogin(auth) }.onFailure { error = it.message }
                        },
                        onCancel = {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    error = NetworkSettings.cancelLogin()
                                } catch (failure: Exception) {
                                    error = failure.message
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
            }
            Text(stringResource(R.string.choose_network_per_server), style = SettingsType.detail)
            OutlinedTextField(
                control,
                { control = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.control_server_optional)) },
                placeholder = { Text("https://headscale.example.com") },
                singleLine = true,
            )
            Text(stringResource(R.string.control_server_hint), style = SettingsType.detail)
            OutlinedTextField(
                deviceName,
                { deviceName = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.device_name)) },
                placeholder = { Text(TailscaleHostname.DEFAULT) },
                singleLine = true,
            )
            Text(stringResource(R.string.device_name_hint), style = SettingsType.detail)
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        error = NetworkSettings.prepareBuiltIn(control, deviceName)
                        busy = false
                    }
                },
            ) {
                Text(
                    if (busy) stringResource(R.string.applying) else stringResource(R.string.apply),
                )
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = SettingsType.detail)
            }
        }
        if (confirmSignOut)
            SettingsConfirmDialog(
                title = stringResource(R.string.tailscale_sign_out_title),
                body = stringResource(R.string.tailscale_sign_out_confirm),
                confirm = stringResource(R.string.sign_out),
                onConfirm = {
                    confirmSignOut = false
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            error = NetworkSettings.logout()
                        } catch (failure: Exception) {
                            error = failure.message
                        } finally {
                            busy = false
                        }
                    }
                },
                onDismiss = { confirmSignOut = false },
            )
    }
}

@Composable
internal fun PendingTailscaleSignIn(busy: Boolean, onContinue: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !busy, onClick = onContinue) {
            Text(stringResource(R.string.continue_in_browser), style = SettingsType.detail)
        }
        TextButton(enabled = !busy, onClick = onCancel) {
            Text(
                stringResource(R.string.cancel_sign_in),
                style = SettingsType.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal suspend fun awaitTailscaleAuth(readStatus: suspend () -> org.json.JSONObject): String {
    while (true) {
        val latest = readStatus()
        latest
            .optString("error")
            .takeIf { it.isNotBlank() }
            ?.let { throw IllegalStateException(it) }
        val state = latest.optJSONObject("tailscale")
        if (state?.optString("BackendState") == "Running") return ""
        state
            ?.optString("AuthURL")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }
        delay(300)
    }
}

internal fun tailscaleStatusLabel(state: String) =
    when (state) {
        "Running" -> "Connected"
        "NeedsLogin" -> "Sign-in required"
        "NeedsMachineAuth" -> "Device approval required"
        "Off" -> "Not set up"
        "Stopped" -> "Stopped"
        "Starting" -> "Connecting…"
        else -> "Not connected"
    }

@androidx.annotation.StringRes
internal fun tailscaleStatusLabelRes(state: String): Int =
    when (state) {
        "Running" -> R.string.tailscale_running
        "NeedsLogin" -> R.string.tailscale_needs_login
        "NeedsMachineAuth" -> R.string.tailscale_needs_machine_auth
        "Off" -> R.string.tailscale_off
        "Stopped" -> R.string.tailscale_stopped
        "Starting" -> R.string.tailscale_starting
        else -> R.string.tailscale_not_connected
    }

@Composable
internal fun ServerNetworkStatusCard(name: String, route: ServerNetworkRoute, connected: Boolean) {
    SettingsSection(stringResource(R.string.current_server)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    name,
                    style = SettingsType.row,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(route.labelRes),
                    style = SettingsType.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (connected) stringResource(R.string.status_connected)
                else stringResource(R.string.status_disconnected),
                style = SettingsType.detail,
                color =
                    if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal data class TailscalePeerStatus(val name: String, val route: String)

internal fun tailscalePeers(status: org.json.JSONObject?): List<TailscalePeerStatus> {
    val peers = status?.optJSONObject("Peer") ?: return emptyList()
    return peers
        .keys()
        .asSequence()
        .mapNotNull { id ->
            val peer =
                peers.optJSONObject(id)?.takeIf { it.optBoolean("Active") }
                    ?: return@mapNotNull null
            val name =
                peer
                    .optString("HostName")
                    .ifBlank { peer.optString("DNSName").trimEnd('.') }
                    .ifBlank { "Device" }
            val route =
                if (peer.optString("CurAddr").isNotBlank()) "Direct"
                else if (peer.optString("PeerRelay").isNotBlank()) "Peer relay"
                else "Tailscale relay"
            TailscalePeerStatus(name, route)
        }
        .sortedBy { it.name.lowercase() }
        .toList()
}

@Composable
internal fun TailscaleStatusCard(
    state: String,
    busy: Boolean,
    peers: List<TailscalePeerStatus> = emptyList(),
    networkError: String = "",
    onSignIn: () -> Unit,
    onSignOut: () -> Unit = {},
) {
    SettingsSection(stringResource(R.string.builtin_tailscale)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(tailscaleStatusLabelRes(state)), style = SettingsType.row)
                Text(
                    if (state == "Running") stringResource(R.string.tailscale_ready_desc)
                    else if (state == "NeedsMachineAuth")
                        stringResource(R.string.tailscale_machine_auth_desc)
                    else if (state == "Off") stringResource(R.string.tailscale_off_desc)
                    else stringResource(R.string.tailscale_browser_signin_desc),
                    style = SettingsType.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state == "Running")
                TextButton(
                    enabled = !busy,
                    onClick = onSignOut,
                    modifier = Modifier.testTag("Tailscale sign out button"),
                ) {
                    Text(
                        stringResource(R.string.sign_out),
                        style = SettingsType.detail,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            else if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else
                TextButton(onClick = onSignIn) {
                    Text(
                        if (state == "Off") stringResource(R.string.set_up)
                        else stringResource(R.string.sign_in),
                        style = SettingsType.detail,
                    )
                }
        }
        if (state == "Running" && peers.isNotEmpty()) {
            HorizontalDivider(
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                stringResource(R.string.active_connections),
                Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                style = SettingsType.section,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            peers.forEach { peer ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ServerGlyph(ServerIcon.SERVER, Modifier.size(18.dp))
                    Text(
                        peer.name,
                        Modifier.weight(1f),
                        style = SettingsType.row,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = .09f),
                    ) { // alpha-ok decorative tint
                        Text(
                            peer.route,
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = SettingsType.detail,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (networkError.isNotBlank())
            Text(
                networkError,
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                style = SettingsType.detail,
                color = MaterialTheme.colorScheme.error,
            )
    }
}

@Composable
internal fun NetworkPrompts() {
    val status by NetworkSettings.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val host = status.optJSONArray("hosts")?.optJSONObject(0)
    var hidden by remember { mutableStateOf("") }
    val signature = host?.toString().orEmpty()
    if (host != null && hidden != signature)
        AlertDialog(
            onDismissRequest = { hidden = signature },
            title = {
                Text(
                    if (host.optString("previous").isEmpty())
                        stringResource(R.string.trust_ssh_host)
                    else stringResource(R.string.ssh_host_key_changed),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(host.optString("host"))
                    Text(host.optString("type"), style = SettingsType.detail)
                    Text(host.optString("fingerprint"), style = SettingsType.detail)
                    if (host.optString("previous").isNotEmpty())
                        Text(
                            stringResource(
                                R.string.label_previous_host_format,
                                host.optString("previous"),
                            ),
                            style = SettingsType.detail,
                        )
                    Text(
                        stringResource(R.string.trust_host_verify_hint),
                        style = SettingsType.detail,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            NetworkSettings.trust(
                                host.getString("host"),
                                host.getString("fingerprint"),
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.trust_host))
                }
            },
            dismissButton = {
                TextButton(onClick = { hidden = signature }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    else {
        val banners = status.optJSONObject("banners")
        val endpoint = banners?.keys()?.asSequence()?.firstOrNull()
        val message = endpoint?.let { banners.optString(it) }.orEmpty()
        if (message.isNotBlank() && hidden != message)
            AlertDialog(
                onDismissRequest = { hidden = message },
                title = { Text(stringResource(R.string.ssh_signin_title)) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("SSH sign-in", message))
                        },
                    ) {
                        Text(stringResource(R.string.copy_message))
                    }
                },
                dismissButton = {
                    val link = Regex("https?://[^\\s<>]+").find(message)?.value
                    if (link != null)
                        TextButton(
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                            },
                        ) {
                            Text(stringResource(R.string.open_browser))
                        }
                    else
                        TextButton(onClick = { hidden = message }) {
                            Text(stringResource(R.string.close))
                        }
                },
            )
    }
}
