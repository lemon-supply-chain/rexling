package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SshKeysTest {
    @get:Rule val ui = createComposeRule()

    private fun key(): String {
        val pair =
            java.security.KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
        return "-----BEGIN PRIVATE KEY-----\n" +
            android.util.Base64.encodeToString(pair.private.encoded, android.util.Base64.NO_WRAP) +
            "\n-----END PRIVATE KEY-----\n"
    }

    @Test
    fun reusableKeysStayEncryptedAndMetadataChangesDoNotRestartConnections() = runBlocking {
        val text = key()
        val revision = NetworkSettings.revision.value
        val result = NetworkSettings.addKey("Shared key", text, "")
        assertNull(result.error)
        val id = result.id!!
        try {
            assertEquals(revision, NetworkSettings.revision.value)
            assertEquals(id, NetworkSettings.addKey("Duplicate", text, "").id)
            assertNull(NetworkSettings.renameKey(id, "Work key"))
            assertEquals(revision, NetworkSettings.revision.value)
            assertNull(NetworkSettings.assignKey("ssh://a", id))
            assertNull(NetworkSettings.assignKey("ssh://b", id))
            assertEquals(id, NetworkSettings.selectedKey("ssh://a"))
            assertEquals(id, NetworkSettings.selectedKey("ssh://b"))
            val stored =
                InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getSharedPreferences("network", 0)
                    .getString("ssh_library", "")!!
            assertFalse(stored.contains("PRIVATE KEY"))
            assertFalse(stored.contains(text))
            assertTrue(stored.contains("SHA256:"))
            assertNull(NetworkSettings.removeKey("ssh://a"))
            assertEquals(id, NetworkSettings.selectedKey("ssh://b"))
            assertTrue(NetworkSettings.sshKeys.value.any { it.id == id })
            assertNotNull(NetworkSettings.addKey("Bad", "not a key", "").error)
        } finally {
            NetworkSettings.deleteKey(id)
        }
        assertNull(NetworkSettings.selectedKey("ssh://b"))
    }

    @Test
    fun legacyEndpointKeysMigrateUsingTheSameKeystoreCiphertext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("network", 0)
        val id = NetworkSettings.addKey("Legacy", key(), "").id!!
        val original = prefs.getString("ssh_library", null)
        val encrypted = SshKeyLibrary(original).secret(id)
        try {
            prefs
                .edit()
                .remove("ssh_library")
                .putString(
                    "keys",
                    JSONObject()
                        .put("ssh://legacy-a", encrypted)
                        .put("ssh://legacy-b", encrypted)
                        .toString(),
                )
                .commit()
            NetworkSettings::class
                .java
                .getDeclaredMethod("migrateKeys")
                .apply { isAccessible = true }
                .invoke(NetworkSettings)
            val a = NetworkSettings.selectedKey("ssh://legacy-a")!!
            assertEquals(a, NetworkSettings.selectedKey("ssh://legacy-b"))
            assertEquals(encrypted, SshKeyLibrary(prefs.getString("ssh_library", null)).secret(a))
            assertFalse(prefs.contains("keys"))
            assertEquals(1, NetworkSettings.sshKeys.value.size)
            assertTrue(NetworkSettings.sshKeys.value.single().fingerprint.startsWith("SHA256:"))
        } finally {
            prefs.edit().putString("ssh_library", original).remove("keys").commit()
            NetworkSettings::class
                .java
                .getDeclaredMethod("migrateKeys")
                .apply { isAccessible = true }
                .invoke(NetworkSettings)
            NetworkSettings.deleteKey(id)
        }
    }

    @Test
    fun serverSelectorReusesASavedKeyAndCanSwitchBackToNoKey() {
        val id = runBlocking { NetworkSettings.addKey("Selector key", key(), "").id!! }
        val selected = mutableStateOf<String?>(null)
        try {
            ui.setContent {
                RexTheme(true, ThemeCatalog.defaults[0]) {
                    SshKeyEditor(selected.value) { selected.value = it }
                }
            }
            ui.onNodeWithText("No key", useUnmergedTree = true).performClick()
            ui.onNodeWithText("Selector key", useUnmergedTree = true).performClick()
            ui.runOnIdle { assertEquals(id, selected.value) }
            ui.onNodeWithText("Selector key", useUnmergedTree = true).performClick()
            ui.onNodeWithText("No key (Tailscale SSH)", useUnmergedTree = true).performClick()
            ui.runOnIdle { assertNull(selected.value) }
            assertTrue(NetworkSettings.sshKeys.value.any { it.id == id })
        } finally {
            runBlocking { NetworkSettings.deleteKey(id) }
        }
    }

    @Test
    fun keyLibraryCanRenameAndDeleteWithoutTouchingAnotherKey() {
        val id = runBlocking { NetworkSettings.addKey("Old name", key(), "").id!! }
        try {
            ui.setContent {
                RexTheme(true, ThemeCatalog.defaults[0]) {
                    Surface(Modifier.fillMaxSize()) { SshKeysSheet({}) }
                }
            }
            ui.onNodeWithText("Old name").assertIsDisplayed()
            ui.waitForIdle()
            Thread.sleep(300)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            File(instrumentation.targetContext.cacheDir, "ssh-key-library-dark.png")
                .outputStream()
                .use {
                    instrumentation.uiAutomation
                        .takeScreenshot()
                        .compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            ui.onNodeWithText("Old name").performClick()
            ui.onNodeWithText(appString(R.string.name)).performTextReplacement("New name")
            ui.onNodeWithText("Save", useUnmergedTree = true).performClick()
            ui.waitUntil(5000) {
                NetworkSettings.sshKeys.value.any { it.id == id && it.name == "New name" }
            }
            ui.onNodeWithContentDescription("Delete SSH key New name").performClick()
            ui.onNodeWithText("Delete", useUnmergedTree = true).performClick()
            ui.waitUntil(5000) { NetworkSettings.sshKeys.value.none { it.id == id } }
        } finally {
            runBlocking { NetworkSettings.deleteKey(id) }
        }
    }
}
