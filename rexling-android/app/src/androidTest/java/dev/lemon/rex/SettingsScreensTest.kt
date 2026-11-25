package dev.lemon.rex

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated settings previews: never connect to or resize a user's Rex pane. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class SettingsScreensTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun settingsRemainReadableInBothThemes() {
        var dark by mutableStateOf(false)
        ui.setContent {
            RexTheme(dark, ThemeCatalog.defaults[if (dark) 0 else 1]) {
                // Match the workspace: no parent Surface providing a text color.
                PreferencesSheet({}, {}, {}, {}, {})
            }
        }
        for (isDark in listOf(false, true)) {
            ui.runOnIdle { dark = isDark }
            var layout: androidx.compose.ui.text.TextLayoutResult? = null
            ui.onNodeWithContentDescription(appString(R.string.close_preferences))
                .assertIsDisplayed()
            ui.onNodeWithText(appString(R.string.preferences))
                .assertIsDisplayed()
                .performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
                ) { action ->
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    action(results)
                    layout = results.first()
                }
            val luminance = layout!!.layoutInput.style.color.luminance()
            assertTrue(
                "Preference header must contrast with its background: dark=$isDark luminance=$luminance",
                if (isDark) luminance > .5f else luminance < .3f,
            )
            ui.onNodeWithText(appString(R.string.background_alerts))
                .performScrollTo()
                .assertIsDisplayed()
            ui.onNodeWithText("Portside").assertDoesNotExist()
            capture("preferences", isDark)
        }
    }

    @Test
    fun expandedPreferencesSheetOnlyDismissesFromTopHandleZone() {
        var closed = 0
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                PreferencesSheet({}, {}, {}, {}, onClose = { closed++ })
            }
        }
        val handleZone = ui.onNodeWithTag("Preferences handle zone")
        ui.awaitDisplayed(handleZone)
        handleZone.performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 600f, durationMillis = 150)
        }
        ui.waitForIdle()
        val expandedTop = handleZone.fetchSemanticsNode().boundsInRoot.top
        ui.onNodeWithText(appString(R.string.appearance)).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 500f, durationMillis = 150)
        }
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(0, closed) }
        assertEquals(expandedTop, handleZone.fetchSemanticsNode().boundsInRoot.top, 2f)

        val bodyArea = ui.onNodeWithTag("Preferences body area")
        bodyArea.performTouchInput {
            swipeDown(
                startY = top + 120f,
                endY = top + 620f,
                durationMillis = 150,
            )
            // Swipe along the horizontal margin / blank edge too.
        }
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(0, closed) }
        assertEquals(expandedTop, handleZone.fetchSemanticsNode().boundsInRoot.top, 2f)

        bodyArea.performTouchInput {
            val marginStart = Offset(left + 4f, top + 120f)
            down(marginStart)
            moveBy(Offset(0f, 400f))
            up()
        }
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(0, closed) }
        assertEquals(expandedTop, handleZone.fetchSemanticsNode().boundsInRoot.top, 2f)

        bodyArea.performTouchInput {
            val bottomBlankStart = Offset(centerX, (bottom - 8f).coerceAtLeast(top + 20f))
            down(bottomBlankStart)
            moveBy(Offset(0f, 300f))
            up()
        }
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(0, closed) }
        assertEquals(expandedTop, handleZone.fetchSemanticsNode().boundsInRoot.top, 2f)

        handleZone.performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 100)
        }
        ui.waitForIdle()
        assertTrue(
            closed > 0 || handleZone.fetchSemanticsNode().boundsInRoot.top > expandedTop + 20f,
        )
    }

    @Test
    fun drawerHandleLightsUpWhenPullingDownFromTop() {
        var actionArgb = 0
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                actionArgb = MaterialTheme.colorScheme.roles.action.toArgb()
                PreferencesSheet({}, {}, {}, {}, onClose = {})
            }
        }
        val handleZone = ui.onNodeWithTag("Preferences handle zone")
        val drawerHandle = ui.onNodeWithTag("Preferences drawer handle")
        ui.awaitDisplayed(handleZone)
        handleZone.performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 600f, durationMillis = 150)
        }
        ui.waitForIdle()

        fun countActionPixels(bitmap: Bitmap): Int {
            var count = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getPixel(x, y) == actionArgb) count++
                }
            }
            return count
        }

        assertEquals(0, countActionPixels(drawerHandle.captureToImage().asAndroidBitmap()))

        handleZone.performTouchInput {
            down(center)
            moveBy(Offset(0f, 80f))
        }
        ui.waitForIdle()
        ui.waitUntil(2_000) {
            countActionPixels(drawerHandle.captureToImage().asAndroidBitmap()) > 0
        }

        handleZone.performTouchInput { cancel() }
        ui.waitForIdle()
        ui.waitUntil(2_000) {
            countActionPixels(drawerHandle.captureToImage().asAndroidBitmap()) == 0
        }
    }

    @Test
    fun systemBackClosesTheExpandedPreferencesSheet() {
        var closed = 0
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                PreferencesSheet({}, {}, {}, {}, onClose = { closed++ })
            }
        }
        val handleZone = ui.onNodeWithTag("Preferences handle zone")
        ui.awaitDisplayed(handleZone)
        handleZone.performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 600f, durationMillis = 150)
        }
        ui.waitForIdle()
        // Delivered synchronously so the event cannot leak into the next test's sheet.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000) { closed > 0 }
        ui.waitForIdle()
        assertEquals(1, closed)
    }

    private fun capture(name: String, dark: Boolean) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Allow the platform dialog/window animation to reach the screenshot surface.
        Thread.sleep(300)
        val large = instrumentation.targetContext.resources.configuration.fontScale > 1.2f
        File(
                instrumentation.targetContext.cacheDir,
                "settings-$name-${if(dark) "dark" else "light"}${if(large) "-large-text" else ""}.png",
            )
            .outputStream()
            .use {
                instrumentation.uiAutomation
                    .takeScreenshot()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
    }
}
