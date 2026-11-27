package dev.lemon.rex

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.NetworkInterface
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class RexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PortForwards.initialize(this)
        NetworkSettings.initialize(this)
    }
}

/** Keys are encrypted individually with an app-only, non-exportable Android Keystore key. */
internal object NetworkSettings {
    private lateinit var context: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val isForeground = MutableStateFlow(true)
    private val _status = MutableStateFlow(JSONObject())
    val status: StateFlow<JSONObject> = _status.asStateFlow()
    val configurationError = MutableStateFlow<String?>(null)
    val revision = MutableStateFlow(0L)
    val sshKeys = MutableStateFlow<List<SshKeyInfo>>(emptyList())
    private val serverChecks = Dispatchers.IO.limitedParallelism(2)
    val sshKeyBindings = MutableStateFlow<Map<String, String>>(emptyMap())
    private val initDeferred = CompletableDeferred<String?>()
    val initialization: Deferred<String?>
        get() = initDeferred

    suspend fun awaitInitialized(): String? =
        withTimeoutOrNull(5000L) { initDeferred.await() }
            ?: run {
                if (!initDeferred.isCompleted)
                    initDeferred.complete("Network initialization timed out")
                initDeferred.await()
            }

    private val preferences
        get() = context.getSharedPreferences("network", Context.MODE_PRIVATE)

    val builtIn
        get() = preferences.getBoolean("built_in", false)

    fun route(endpoint: String) =
        ServerNetworkRoute.decode(
            JSONObject(preferences.getString("routes", "{}")!!).optString(endpoint),
        )

    val tailscaleEnabled
        get() =
            !preferences.getBoolean("tailscale_suspended", false) &&
                (builtIn ||
                    preferences.getBoolean("tailscale_prepared", false) ||
                    JSONObject(preferences.getString("routes", "{}")!!).let { routes ->
                        routes.keys().asSequence().any { routes.optString(it) == "builtin" }
                    })

    val controlUrl
        get() = preferences.getString("control_url", "").orEmpty()

    /** The built-in Tailscale device name; blank means the default, rexling-android. */
    val hostname
        get() = preferences.getString("tailscale_hostname", "").orEmpty()

    @Synchronized
    private fun vaultKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("rexling-ssh", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "rexling-ssh",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }
            .generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, vaultKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(text: String): String {
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        if (bytes.size < 12) return ""
        return String(
            Cipher.getInstance("AES/GCM/NoPadding")
                .apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        vaultKey(),
                        GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                    )
                }
                .doFinal(bytes.copyOfRange(12, bytes.size)),
        )
    }

    private fun keyLibrary() = SshKeyLibrary(preferences.getString("ssh_library", null))

    fun selectedKey(endpoint: String) = keyLibrary().selected(endpoint)

    suspend fun checkServer(
        endpoint: String,
        route: ServerNetworkRoute,
        keyId: String?,
    ): ServerUrlCheck =
        withContext(serverChecks) {
            val response =
                try {
                    val key = keyId?.let { decrypt(keyLibrary().secret(it)) }.orEmpty()
                    JSONObject(
                        NativeBridge.probeServer(
                            endpoint,
                            route == ServerNetworkRoute.BUILTIN,
                            key,
                        ),
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    return@withContext ServerUrlCheck.Error(R.string.server_check_failed)
                }
            val failure = response.optString("error")
            if (failure.isBlank())
                ServerUrlCheck.Reachable(
                    response
                        .optString("version", "")
                        .takeUnless { response.isNull("version") }
                        .orEmpty()
                        .filterNot { it.isISOControl() }
                        .take(100),
                )
            else ServerUrlCheck.Error(serverCheckFailure(failure))
        }

    fun hasKey(endpoint: String) = selectedKey(endpoint) != null

    fun keyEndpoints(id: String) = keyLibrary().endpoints(id)

    private fun refreshLibrary(library: SshKeyLibrary) {
        sshKeys.value = library.list()
        sshKeyBindings.value = library.assigned()
    }

    private fun saveLibrary(library: SshKeyLibrary) {
        check(preferences.edit().putString("ssh_library", library.encode()).commit()) {
            "Could not save SSH keys"
        }
        refreshLibrary(library)
    }

    private fun migrateKeys() {
        if (!preferences.contains("ssh_library")) {
            val library =
                SshKeyLibrary.migrate(JSONObject(preferences.getString("keys", "{}")!!)) { encrypted
                    ->
                    val info = command("import_key", JSONObject().put("key", decrypt(encrypted)))
                    info.getString("fingerprint") to info.getString("kind")
                }
            check(
                preferences
                    .edit()
                    .putString("ssh_library", library.encode())
                    .remove("keys")
                    .commit(),
            ) {
                "Could not migrate SSH keys"
            }
        }
        refreshLibrary(keyLibrary())
    }

    private fun command(type: String, body: JSONObject = JSONObject()): JSONObject =
        JSONObject(NativeBridge.networkCommand(body.put("type", type).toString()))

    fun initialize(application: Context) {
        context = application.applicationContext as Application
        context.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var startedActivities = 0

                override fun onActivityStarted(activity: Activity) {
                    startedActivities++
                    isForeground.value = true
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities = (startedActivities - 1).coerceAtLeast(0)
                    if (startedActivities == 0) isForeground.value = false
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

                override fun onActivityResumed(activity: Activity) {}

                override fun onActivityPaused(activity: Activity) {}

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
        scope.launch {
            try {
                if (!preferences.getBoolean("per_server_routes", false)) {
                    val app = context.getSharedPreferences("rex", Context.MODE_PRIVATE)
                    val endpoints =
                        SavedServers.decode(
                                app.getString("servers", null),
                                app.getString("endpoint", "").orEmpty(),
                            )
                            .map { it.endpoint }
                    val routes =
                        migratedServerRoutes(
                            JSONObject(preferences.getString("routes", "{}")!!),
                            endpoints,
                            builtIn,
                        )
                    preferences
                        .edit()
                        .putString("routes", routes.toString())
                        .putBoolean("tailscale_prepared", tailscaleEnabled)
                        .putBoolean("built_in", false)
                        .putBoolean("per_server_routes", true)
                        .apply()
                }
                migrateKeys()
                // Install configuration before any terminal or monitoring service can connect.
                interfaces()
                val error = configure()
                val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
                runCatching {
                    manager?.registerDefaultNetworkCallback(
                        object : android.net.ConnectivityManager.NetworkCallback() {
                            override fun onAvailable(network: android.net.Network) {
                                scope.launch { interfaces() }
                            }

                            override fun onLost(network: android.net.Network) {
                                scope.launch { interfaces() }
                            }

                            override fun onLinkPropertiesChanged(
                                network: android.net.Network,
                                properties: android.net.LinkProperties,
                            ) {
                                scope.launch { interfaces() }
                            }
                        },
                    )
                }
                initDeferred.complete(error)
            } catch (t: Throwable) {
                val err = t.message ?: "Network initialization failed"
                configurationError.value = err
                initDeferred.complete(err)
            }
        }
        startStatusPolling()
    }

    private fun startStatusPolling() {
        scope.launch {
            combine(_status.subscriptionCount, isForeground) { count, fg -> count > 0 && fg }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active) {
                        var failureCount = 0
                        while (isActive) {
                            val result = runCatching { command("status") }
                            if (result.isSuccess) {
                                val newStatus = result.getOrThrow()
                                configurationError.value?.let { cfgErr ->
                                    if (
                                        !newStatus.has("error") ||
                                            newStatus.optString("error").isEmpty()
                                    ) {
                                        newStatus.put("error", cfgErr)
                                    }
                                }
                                _status.value = newStatus
                                failureCount = 0
                            } else {
                                failureCount++
                            }
                            val baseDelay = if (tailscaleEnabled) 3000L else 8000L
                            val backoffDelay =
                                if (failureCount > 0)
                                    (baseDelay * (1 + failureCount)).coerceAtMost(30000L)
                                else baseDelay
                            delay(backoffDelay)
                        }
                    }
                }
        }
    }

    private fun interfaces() {
        val list = JSONArray()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { item ->
                var flags = 0
                if (item.isUp) flags = flags or 1
                if (item.interfaceAddresses.any { it.broadcast != null }) flags = flags or 2
                if (item.isLoopback) flags = flags or 4
                if (item.isPointToPoint) flags = flags or 8
                if (item.supportsMulticast()) flags = flags or 16
                list.put(
                    JSONObject()
                        .put("name", item.name)
                        .put("index", item.index)
                        .put("mtu", item.mtu)
                        .put("flags", flags)
                        .put(
                            "addresses",
                            JSONArray(
                                item.interfaceAddresses.map {
                                    "${it.address.hostAddress?.substringBefore('%')}/${it.networkPrefixLength}"
                                },
                            ),
                        ),
                )
            }
        }
        command("interfaces", JSONObject().put("interfaces", list))
    }

    private fun configure(): String? {
        val ssh = JSONObject()
        val library = keyLibrary()
        var decryptError: String? = null
        library.assigned().forEach { (endpoint, id) ->
            runCatching {
                val secret = library.secret(id)
                val key = decrypt(secret)
                if (key.isBlank()) throw IllegalStateException("Decrypted SSH key is empty")
                ssh.put(endpoint, JSONObject().put("key", key))
            }
                .onFailure { failure ->
                    if (decryptError == null) {
                        decryptError =
                            "Failed to decrypt SSH key for $endpoint: ${failure.message ?: failure.javaClass.simpleName}"
                    }
                }
        }
        val config =
            JSONObject()
                .put("built_in", builtIn)
                .put("control_url", controlUrl)
                .put("hostname", hostname)
                .put("directory", context.filesDir.resolve("tailscale").absolutePath)
                .put("built_in_suspended", preferences.getBoolean("tailscale_suspended", false))
                .put("built_in_enabled", tailscaleEnabled)
                .put("routes", JSONObject(preferences.getString("routes", "{}")!!))
                .put("ssh", ssh)
                .put("trusted", JSONObject(preferences.getString("trusted", "{}")!!))
        val commandResult = command("configure", JSONObject().put("config", config))
        val commandError = commandResult.optString("error").takeIf { it.isNotEmpty() }
        val error = decryptError ?: commandError
        configurationError.value = error
        if (error == null) {
            revision.value = revision.value + 1
        } else {
            val current = JSONObject(_status.value.toString())
            current.put("error", error)
            _status.value = current
        }
        return error
    }

    suspend fun reconfigure(): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                interfaces()
                configure()
            }
        }

    suspend fun save(enabled: Boolean, url: String): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val trimmed = url.trim().trimEnd('/')
                if (trimmed.isNotEmpty()) {
                    val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
                    if (
                        uri == null ||
                            uri.scheme !in listOf("https", "http") ||
                            uri.host.isNullOrEmpty() ||
                            uri.userInfo != null ||
                            uri.query != null ||
                            uri.fragment != null
                    )
                        return@withLock "Enter a complete control server HTTP(S) URL"
                }
                preferences
                    .edit()
                    .putBoolean("built_in", enabled)
                    .apply { if (enabled) putBoolean("tailscale_suspended", false) }
                    .putString("control_url", trimmed)
                    .commit()
                configure()
            }
        }

    suspend fun saveRoute(endpoint: String, route: ServerNetworkRoute): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val routes = JSONObject(preferences.getString("routes", "{}")!!)
                if (ServerNetworkRoute.decode(routes.optString(endpoint)) == route)
                    return@withLock null
                routes.put(endpoint, route.key)
                preferences.edit().putString("routes", routes.toString()).commit()
                configure()
            }
        }

    suspend fun prepareBuiltIn(url: String, name: String = hostname): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val trimmed = url.trim().trimEnd('/')
                if (trimmed.isNotEmpty()) {
                    val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
                    if (
                        uri == null ||
                            uri.scheme !in listOf("http", "https") ||
                            uri.host.isNullOrEmpty() ||
                            uri.userInfo != null ||
                            uri.query != null ||
                            uri.fragment != null
                    )
                        return@withLock "Enter a complete control server HTTP(S) URL"
                }
                val device =
                    TailscaleHostname.normalize(name)
                        ?: return@withLock context.getString(R.string.device_name_error)
                preferences
                    .edit()
                    .putBoolean("tailscale_suspended", false)
                    .putBoolean("tailscale_prepared", true)
                    .putString("control_url", trimmed)
                    .putString("tailscale_hostname", device)
                    .commit()
                configure()
            }
        }

    suspend fun cancelLogin(): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val response = command("cancel_login")
                val error = response.optString("error").takeIf { it.isNotBlank() }
                if (error == null) {
                    preferences
                        .edit()
                        .putBoolean("tailscale_suspended", true)
                        .putBoolean("tailscale_prepared", false)
                        .commit()
                    // Do not reconfigure or bump the revision: phone-network SSH/Rex
                    // connections must remain untouched by cancelling an enrollment.
                }
                val s = command("status")
                configurationError.value?.let { cfgErr ->
                    if (!s.has("error") || s.optString("error").isEmpty()) s.put("error", cfgErr)
                }
                _status.value = s
                error
            }
        }

    suspend fun logout(): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val response = command("logout")
                val error = response.optString("error").takeIf { it.isNotBlank() }
                if (error == null) {
                    preferences
                        .edit()
                        .putBoolean("tailscale_suspended", true)
                        .putBoolean("tailscale_prepared", false)
                        .commit()
                }
                val s = command("status")
                configurationError.value?.let { cfgErr ->
                    if (!s.has("error") || s.optString("error").isEmpty()) s.put("error", cfgErr)
                }
                _status.value = s
                error
            }
        }

    suspend fun refreshStatus() =
        withContext(Dispatchers.IO) {
            val s = command("status")
            configurationError.value?.let { cfgErr ->
                if (!s.has("error") || s.optString("error").isEmpty()) s.put("error", cfgErr)
            }
            _status.value = s
        }

    suspend fun addKey(name: String, text: String, passphrase: String): SshKeyImportResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (name.trim().isEmpty() || name.trim().length > 80)
                    return@withLock SshKeyImportResult(
                        error = "Enter a key name of up to 80 characters",
                    )
                if (text.toByteArray().size > 131072)
                    return@withLock SshKeyImportResult(error = "SSH key is too large")
                val response =
                    command(
                        "import_key",
                        JSONObject().put("key", text).put("passphrase", passphrase),
                    )
                if (response.has("error"))
                    return@withLock SshKeyImportResult(error = response.getString("error"))
                val library = keyLibrary()
                val id =
                    library.add(
                        name,
                        response.getString("fingerprint"),
                        response.getString("kind"),
                        encrypt(response.getString("key")),
                    )
                saveLibrary(library) // An unassigned key must not restart any connections.
                SshKeyImportResult(id = id)
            }
        }

    suspend fun assignKey(endpoint: String, id: String?): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val library = keyLibrary()
                if (library.selected(endpoint) == id) return@withLock null
                library.assign(endpoint, id)
                saveLibrary(library)
                configure()
            }
        }

    suspend fun renameKey(id: String, name: String): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val library = keyLibrary()
                library.rename(id, name)
                saveLibrary(library)
                null
            }
        }

    suspend fun deleteKey(id: String): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val library = keyLibrary()
                val used = library.endpoints(id).isNotEmpty()
                library.remove(id)
                saveLibrary(library)
                if (used) configure() else null
            }
        }

    suspend fun importKey(endpoint: String, text: String, passphrase: String): String? {
        val result =
            addKey(
                "${runCatching {java.net.URI(endpoint).host}.getOrNull() ?: "Imported"} key",
                text,
                passphrase,
            )
        return result.error ?: assignKey(endpoint, result.id)
    }

    suspend fun removeKey(endpoint: String) = assignKey(endpoint, null)

    suspend fun trust(host: String, fingerprint: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val trusted =
                    JSONObject(preferences.getString("trusted", "{}")!!).put(host, fingerprint)
                preferences.edit().putString("trusted", trusted.toString()).commit()
                configure()
            }
        }

    fun forget(endpoint: String, remaining: List<String>) {
        scope.launch {
            mutex.withLock {
                val library = keyLibrary()
                if (endpoint !in remaining) library.assign(endpoint, null)
                val trusted = JSONObject(preferences.getString("trusted", "{}")!!)
                fun authority(value: String): String? = runCatching {
                    val uri = java.net.URI(value)
                    val host = uri.host.removePrefix("[").removeSuffix("]")
                    if (uri.scheme != "ssh") null
                    else
                        "${if(host.contains(':')) "[$host]" else host}:${if(uri.port<0) 22 else uri.port}"
                }
                    .getOrNull()
                authority(endpoint)?.let { host ->
                    if (remaining.none { authority(it) == host }) trusted.remove(host)
                }
                val routes = JSONObject(preferences.getString("routes", "{}")!!)
                if (endpoint !in remaining) routes.remove(endpoint)
                saveLibrary(library)
                preferences
                    .edit()
                    .putString("trusted", trusted.toString())
                    .putString("routes", routes.toString())
                    .commit()
                configure()
            }
        }
    }

    suspend fun login(): String? =
        withContext(Dispatchers.IO) {
            command("login").optString("error").takeIf { it.isNotEmpty() }
        }
}

/**
 * Validation for the built-in Tailscale device name: one DNS label, as Tailscale machine names are.
 */
internal object TailscaleHostname {
    const val DEFAULT = "rexling-android"
    private val label = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

    /**
     * The name to store: blank for the default, lower-cased otherwise; null if Tailscale would not
     * accept it.
     */
    fun normalize(raw: String): String? {
        val name = raw.trim().lowercase()
        if (name.isEmpty()) return ""
        return name.takeIf { label.matches(it) }
    }
}
