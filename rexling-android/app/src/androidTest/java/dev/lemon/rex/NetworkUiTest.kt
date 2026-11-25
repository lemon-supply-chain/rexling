package dev.lemon.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NetworkUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pastedPrivateKeyUsesEncryptedStorageAndCanBeCancelled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val endpoint = "ssh://paste-key-test@127.0.0.1:2222"
        val pair =
            java.security.KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
        val key =
            "-----BEGIN PRIVATE KEY-----\n" +
                android.util.Base64.encodeToString(
                    pair.private.encoded,
                    android.util.Base64.NO_WRAP,
                ) +
                "\n-----END PRIVATE KEY-----\n"
        runBlocking { NetworkSettings.removeKey(endpoint) }
        var selected by androidx.compose.runtime.mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme { Column { SshKeyEditor(selected) { selected = it } } } }
        val previous = clipboard.primaryClip
        try {
            compose.runOnIdle {
                clipboard.setPrimaryClip(ClipData.newPlainText("Test SSH key", key))
            }
            compose.onNodeWithText(appString(R.string.paste_new_key)).performClick()
            compose.onNodeWithText(appString(R.string.private_key)).assertIsDisplayed()
            compose.onNodeWithText(appString(R.string.cancel)).performClick()
            assertFalse(NetworkSettings.hasKey(endpoint))
            compose.onNodeWithText(appString(R.string.paste_new_key)).performClick()
            compose.onNodeWithText(appString(R.string.key_name)).performTextInput("Pasted test key")
            compose.onNodeWithText(appString(R.string.save_key)).assertIsEnabled().performClick()
            compose.waitUntil(timeoutMillis = 10000) { selected != null }
            compose.onNodeWithText("Pasted test key").assertIsDisplayed()
            assertFalse(
                "Key selection is a draft until the server is saved",
                NetworkSettings.hasKey(endpoint),
            )
            val stored =
                context.getSharedPreferences("network", 0).getString("ssh_library", "").orEmpty()
            assertFalse(stored.contains(key))
            assertFalse(stored.contains("PRIVATE KEY"))
        } finally {
            runBlocking { selected?.let { NetworkSettings.deleteKey(it) } }
            compose.runOnIdle {
                if (previous != null) clipboard.setPrimaryClip(previous)
                else clipboard.clearPrimaryClip()
            }
        }
    }

    @Test
    fun emptyClipboardAllowsManualPasteAndRejectsInvalidKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = clipboard.primaryClip
        var selected by androidx.compose.runtime.mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme { Column { SshKeyEditor(selected) { selected = it } } } }
        try {
            compose.runOnIdle { clipboard.clearPrimaryClip() }
            compose.onNodeWithText(appString(R.string.paste_new_key)).performClick()
            compose.onNodeWithText(appString(R.string.save_key)).assertIsNotEnabled()
            compose.onNodeWithText(appString(R.string.key_name)).performTextInput("Invalid key")
            compose
                .onNodeWithText(appString(R.string.private_key))
                .performTextInput("not a private key")
            compose.onNodeWithText(appString(R.string.save_key)).performClick()
            compose.waitUntil(timeoutMillis = 10000) {
                compose.onAllNodesWithText("ssh: no key found").fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(NetworkSettings.hasKey("ssh://invalid-paste@127.0.0.1:2222"))
            compose.onNodeWithText(appString(R.string.cancel)).performClick()
        } finally {
            compose.runOnIdle {
                if (previous != null) clipboard.setPrimaryClip(previous)
                else clipboard.clearPrimaryClip()
            }
        }
    }

    @Test
    fun networkSettingsExposeOptionalTailscaleAndControlServer() {
        compose.setContent { MaterialTheme { NetworkSheet {} } }
        compose.onNodeWithText(appString(R.string.builtin_tailscale)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.control_server_optional)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.apply)).assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(
                    InstrumentationRegistry.getInstrumentation()
                        .targetContext
                        .getExternalFilesDir(null),
                    "network-settings.png",
                )
                .outputStream()
                .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun serverEditorOffersPerServerNetworkAndSetup() {
        var dark by mutableStateOf(false)
        var editor by mutableStateOf(false)
        compose.setContent {
            RexTheme(dark, ThemeCatalog.defaults[if (dark) 0 else 1]) {
                EmptyServerContent { editor = true }
                if (editor) ServerEditor(null, { editor = false }, { _, _, _ -> null })
            }
        }
        compose.onNodeWithText(appString(R.string.add_server_title)).performClick()
        compose.onNodeWithText(appString(R.string.connection_network)).assertDoesNotExist()
        compose.onNodeWithText(appString(R.string.name)).performTextInput("Shipyard")
        compose.onNodeWithText(appString(R.string.server_url)).performTextInput("https://shipyard")
        compose
            .onNodeWithText(appString(R.string.network_route_system))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(appString(R.string.builtin_tailscale)).performClick()
        compose
            .onNodeWithText(appString(R.string.setup_or_signin))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText(appString(R.string.control_server_optional)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.save_and_connect)).assertDoesNotExist()
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            val sheet = compose.onNodeWithTag("Frosted overlay").captureToImage().toPixelMap()
            val surface =
                rexColorScheme(isDark, ThemeCatalog.defaults[if (isDark) 0 else 1].ui).surface
            assertEquals(surface, sheet[sheet.width / 4, 6])
        }
        compose
            .onNodeWithContentDescription(appString(R.string.close_network_settings))
            .performClick()
        compose.onNodeWithText("Shipyard").assertIsDisplayed()
        compose.onNodeWithText("https://shipyard").assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.setup_or_signin)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.builtin_tailscale)).performClick()
        compose.onNodeWithText(appString(R.string.network_route_system)).performClick()
        compose.onNodeWithText(appString(R.string.system_route_desc)).assertIsDisplayed()
    }

    @Test
    fun signInStatusHasReadableLabelAndBusyFeedback() {
        var busy by androidx.compose.runtime.mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                TailscaleStatusCard(
                    "NeedsLogin",
                    busy,
                    onSignIn = {
                        clicks++
                        busy = true
                    },
                )
            }
        }
        compose.onNodeWithText(appString(R.string.tailscale_needs_login)).assertIsDisplayed()
        compose.onNodeWithText("NeedsLogin").assertDoesNotExist()
        compose.onNodeWithText(appString(R.string.sign_in)).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithText(appString(R.string.sign_in)).assertDoesNotExist()
    }

    @Test
    fun connectedStatusReplacesSignInRatherThanOfferingItAgain() {
        var state by androidx.compose.runtime.mutableStateOf("NeedsLogin")
        compose.setContent { MaterialTheme { TailscaleStatusCard(state, false, onSignIn = {}) } }
        compose.onNodeWithText(appString(R.string.sign_in)).assertIsDisplayed()
        compose.runOnIdle { state = "Running" }
        compose.onNodeWithText(appString(R.string.status_connected)).assertIsDisplayed()
        compose.onNodeWithTag("Tailscale sign out button").assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.sign_in)).assertDoesNotExist()
        compose.onNodeWithText("Sign in again").assertDoesNotExist()
    }

    @Test
    fun emptyServerStateOpensAddServerInsteadOfQuickTailscaleSetup() {
        var added = 0
        compose.setContent { MaterialTheme { EmptyServerContent { added++ } } }
        compose.onNodeWithText(appString(R.string.welcome_title)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.rex_server_url)).assertDoesNotExist()
        compose.onNodeWithText(appString(R.string.connect_over_tailscale)).assertDoesNotExist()
        compose.onNodeWithText(appString(R.string.add_server_title)).performClick()
        compose.runOnIdle { assertEquals(1, added) }
    }

    @Test
    fun connectedDevicesAndRoutesShareTheTailnetStatusCard() {
        var dark by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            RexTheme(dark, ThemeCatalog.defaults[if (dark) 0 else 1]) {
                androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxWidth().padding(20.dp),
                    ) {
                        TailscaleStatusCard(
                            "Running",
                            false,
                            listOf(
                                TailscalePeerStatus("shipyard-rex", "Direct"),
                                TailscalePeerStatus("MacBook", "Tailscale relay"),
                            ),
                            onSignIn = {},
                        )
                    }
                }
            }
        }
        for (value in listOf(false, true)) {
            compose.runOnIdle { dark = value }
            compose.onNodeWithText(appString(R.string.status_connected)).assertIsDisplayed()
            compose.onNodeWithText(appString(R.string.active_connections)).assertIsDisplayed()
            compose.onNodeWithText("shipyard-rex").assertIsDisplayed()
            compose.onNodeWithText("Direct").assertIsDisplayed()
            compose.onNodeWithText(appString(R.string.sign_in)).assertDoesNotExist()
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "tailnet-card-${if(value) "dark" else "light"}.png",
                )
                .outputStream()
                .use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun phoneVpnConnectionIsSeparateFromUnconfiguredBuiltInTailscale() {
        compose.setContent {
            MaterialTheme {
                Column {
                    ServerNetworkStatusCard("Shipyard", ServerNetworkRoute.PHONE, true)
                    TailscaleStatusCard("Off", false, onSignIn = {})
                }
            }
        }
        compose.onNodeWithText(appString(R.string.network_route_system)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.status_connected)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.tailscale_off)).assertIsDisplayed()
        compose.onNodeWithText("Starting…").assertDoesNotExist()
    }

    @Test
    fun pendingSignInCanBeCancelledAndItsBrowserLinkDisappears() {
        var pending by androidx.compose.runtime.mutableStateOf(true)
        var continued = 0
        compose.setContent {
            MaterialTheme {
                if (pending) PendingTailscaleSignIn(false, { continued++ }, { pending = false })
                else TailscaleStatusCard("Off", false, onSignIn = {})
            }
        }
        compose.onNodeWithText(appString(R.string.continue_in_browser)).assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.cancel_sign_in)).performClick()
        compose.onNodeWithText(appString(R.string.continue_in_browser)).assertDoesNotExist()
        compose.onNodeWithText(appString(R.string.tailscale_off)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, continued) }
    }

    @Test
    fun sshEditorOffersKeyImportAndKeylessConnection() {
        compose.setContent {
            MaterialTheme {
                ServerEditor(
                    ServerProfile.create("Test SSH", "ssh://tester@example.com"),
                    {},
                    { _, _, _ -> null },
                )
            }
        }
        compose
            .onNodeWithText(appString(R.string.import_new_key))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.no_key)).assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(
                    InstrumentationRegistry.getInstrumentation()
                        .targetContext
                        .getExternalFilesDir(null),
                    "ssh-settings.png",
                )
                .outputStream()
                .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
