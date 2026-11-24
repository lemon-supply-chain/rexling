package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance
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
