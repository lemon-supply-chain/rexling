package dev.lemon.rex

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeepScreenAwakeUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun modesApplyOnlyToMountedTerminalAndReleaseOnRemoval() {
        lateinit var view: TerminalView
        var mounted by mutableStateOf(true)
        ui.setContent {if(mounted) AndroidView(factory={TerminalView(it).also {v->view=v}},modifier=Modifier.fillMaxSize())}
        ui.waitUntil(5000) {view.hasWindowFocus()}
        ui.runOnIdle {
            assertFalse(view.keepScreenOn)
            view.keepAwakeMode=KeepScreenAwake.VISIBLE;assertTrue(view.keepScreenOn)
            view.keepAwakeMode=KeepScreenAwake.ACTIVE;assertFalse(view.keepScreenOn)
            view.screen=TerminalScreen(40,20,0,0,-1,0,emptyList());assertTrue(view.keepScreenOn)
        }
        ui.waitUntil(2000) {!view.keepScreenOn}
        ui.runOnIdle {
            view.keepAwakeMode=KeepScreenAwake.SYSTEM;assertFalse(view.keepScreenOn)
            view.keepAwakeMode=KeepScreenAwake.VISIBLE;assertTrue(view.keepScreenOn)
            mounted=false
        }
        ui.runOnIdle {assertFalse(view.isAttachedToWindow);assertFalse(view.keepScreenOn)}
    }
}
