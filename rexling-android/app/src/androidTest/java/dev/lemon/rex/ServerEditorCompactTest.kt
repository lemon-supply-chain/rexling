package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ServerEditorCompactTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun savedKeySelectorAndTheEditorFitBothThemes() {
        val endpoint = "ssh://compact-ui@127.0.0.1:2222"
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
        runBlocking { assertNull(NetworkSettings.importKey(endpoint, key, "")) }
        var dark by mutableStateOf(false)
        try {
            ui.setContent {
                RexTheme(dark, ThemeCatalog.defaults[if (dark) 0 else 1]) {
                    ServerEditor(
                        ServerProfile.create("Shipyard 2", endpoint),
                        {},
                        { _, _, _ -> null },
                    )
                }
            }
            for (value in listOf(false, true)) {
                ui.runOnIdle { dark = value }
                ui.onNodeWithText(appString(R.string.save_and_connect)).assertIsDisplayed()
                // The editor selects a saved key; file/paste actions live in the keys sheet.
                ui.onNodeWithText(appString(R.string.ssh_key)).assertIsDisplayed()
                ui.onNodeWithContentDescription(appString(R.string.a11y_choose_server_icon))
                    .assertIsDisplayed()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                ui.waitForIdle()
                instrumentation.waitForIdleSync()
                Thread.sleep(300)
                File(
                        instrumentation.targetContext.cacheDir,
                        "server-editor-compact-${if(value) "dark" else "light"}.png",
                    )
                    .outputStream()
                    .use {
                        instrumentation.uiAutomation
                            .takeScreenshot()
                            .compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
            }
        } finally {
            runBlocking { NetworkSettings.removeKey(endpoint) }
        }
    }
}
