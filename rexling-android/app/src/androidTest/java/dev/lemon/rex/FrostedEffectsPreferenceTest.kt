package dev.lemon.rex

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrostedEffectsPreferenceTest {
    @get:Rule val ui=createComposeRule()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Test fun preferenceDefaultsOnAndSurvivesModelRecreation() {
        val name="frosted-effects-${System.nanoTime()}";val store=ViewModelStore()
        fun main(block: ()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        try {
            main {
                val first=RexModel(app,name);store.put("frost-1",first)
                assertTrue(first.frostedEffectsEnabled)
                first.changeFrostedEffects(false)
                assertFalse(first.frostedEffectsEnabled)
                val second=RexModel(app,name);store.put("frost-2",second)
                assertFalse(second.frostedEffectsEnabled)
            }
        } finally {
            main {store.clear()}
            app.deleteSharedPreferences(name)
        }
    }

    @Test fun frostedOptionTurnsOpaqueImmediatelyInBothThemes() {
        val dark=mutableStateOf(false);val enabled=mutableStateOf(true);var calls=0
        ui.setContent {
            RexTheme(dark.value,ThemeCatalog.defaults[if(dark.value) 0 else 1],frostedEffectsEnabled=enabled.value) {
                PreferencesSheet({},{},{},{},{},frostedEffectsEnabled=enabled.value,onFrostedEffects={enabled.value=it;calls++})
            }
        }
        for(isDark in listOf(false,true)) {
            ui.runOnIdle {dark.value=isDark;enabled.value=true}
            val toggle=ui.onNode(hasText("Frosted glass effects") and isToggleable())
            toggle.performScrollTo().assertIsOn().performClick().assertIsOff()
            capture(isDark)
            toggle.performClick().assertIsOn()
        }
        ui.runOnIdle {assertEquals(4,calls)}
    }

    @Test fun disabledFrostIsOpaqueAndIgnoresBackdropChanges() {
        val green=mutableStateOf(false)
        ui.setContent {
            RexTheme(false,ThemeCatalog.defaults[1],frostedEffectsEnabled=false) {
                val haze=remember {HazeState()}
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().hazeSource(haze)) {
                        drawRect(if(green.value) Color.Green else Color.Red)
                    }
                    Box(Modifier.fillMaxSize().testTag("Frost under test").paneFrost(haze,RectangleShape,darkSurface=true,edge=false))
                }
            }
        }
        fun sample(): Color {
            val image=ui.onNodeWithTag("Frost under test").captureToImage()
            return image.toPixelMap()[image.width/2,image.height/2]
        }
        val expected=Color(0xff171a20)
        assertEquals(expected,sample())
        ui.runOnIdle {green.value=true}
        ui.waitForIdle();Thread.sleep(80)
        assertEquals(expected,sample())
    }

    private fun capture(dark: Boolean) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        File(instrumentation.targetContext.cacheDir,"settings-opaque-${if(dark) "dark" else "light"}.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
