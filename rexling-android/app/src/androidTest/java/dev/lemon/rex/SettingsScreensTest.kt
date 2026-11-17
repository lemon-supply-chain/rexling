package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated settings previews: never connect to or resize a user's Rex pane. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class SettingsScreensTest {
    @get:Rule val ui=createComposeRule()
    @Test fun settingsRemainReadableInBothThemes() {
        var dark by mutableStateOf(false)
        ui.setContent {
            RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
                Surface(Modifier.fillMaxSize()) {
                    PreferencesSheet({},{},{},{},{})
                }
            }
        }
        for(isDark in listOf(false,true)) {
            ui.runOnIdle {dark=isDark}
            ui.onNodeWithText("Background alerts").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Portside").assertDoesNotExist()
            capture("preferences",isDark)

        }
    }
    private fun capture(name: String,dark: Boolean) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Allow the platform dialog/window animation to reach the screenshot surface.
        Thread.sleep(300)
        val large=instrumentation.targetContext.resources.configuration.fontScale>1.2f
        File(instrumentation.targetContext.cacheDir,"settings-$name-${if(dark) "dark" else "light"}${if(large) "-large-text" else ""}.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
