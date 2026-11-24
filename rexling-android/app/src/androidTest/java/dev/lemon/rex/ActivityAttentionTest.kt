package dev.lemon.rex

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityAttentionTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun blockedServerReportReachesTheVisibleEyeBadgeAndAcknowledgmentClearsIt() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("attention_server")
        val session = args.getString("attention_session")
        assumeTrue(
            "Needs an explicitly owned disposable fixture",
            endpoint != null && session != null,
        )
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val name = "rexlink-attention-test-${System.nanoTime()}"
        val store = ViewModelStore()
        val profile = ServerProfile.create("Attention fixture", endpoint!!, "fixture")
        app.getSharedPreferences(name, 0)
            .edit()
            .putString("servers", SavedServers.encode(listOf(profile)))
            .putString("active_server", profile.id)
            .putString("current_session:$endpoint", session)
            .commit()
        lateinit var model: RexModel
        try {
            ui.runOnUiThread {
                model = RexModel(app, name)
                store.put("attention", model)
                model.connectServer(profile.id)
            }
            ui.setContent {
                MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xff315cf4))) {
                    TerminalActions(true, true, {}, {}, attentionCount = model.attentionCount)
                }
            }
            ui.waitUntil(20000) {
                model.activeClient.state.value.connected && model.attentionCount > 0
            }
            val eye = ui.onNodeWithContentDescription("Activity")
            eye.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "${model.attentionCount} new activity items",
                ),
            )
            fun bluePixels(): Int {
                val bitmap = eye.captureToImage().asAndroidBitmap()
                var count = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    if (bitmap.getPixel(x, y) == 0xff315cf4.toInt()) count++
                }
                return count
            }
            assertTrue(
                "The real report must draw a visible, unclipped blue badge",
                bluePixels() > 10,
            )
            File(app.cacheDir, "activity-attention.png").outputStream().use {
                ui.onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            ui.runOnIdle { model.showActivity(true) }
            ui.waitUntil(20000) {
                model.activitySources.any {
                    it.items.any { record ->
                        record.session == session && record.state == ActivityState.BLOCKED
                    }
                }
            }
            ui.runOnIdle { model.markAllActivitySeen() }
            ui.waitUntil(20000) { model.attentionCount == 0 }
            eye.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "All activity seen",
                ),
            )
            assertEquals(0, bluePixels())
        } finally {
            ui.runOnUiThread { store.clear() }
            app.deleteSharedPreferences(name)
        }
    }
}
