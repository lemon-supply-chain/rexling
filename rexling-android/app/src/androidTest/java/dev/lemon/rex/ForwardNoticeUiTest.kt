package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ForwardNoticeUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun readyNoticeKeepsActionsCompactAndDismisses() {
        val original=PortForwards.linkNotice
        try {
            ui.runOnIdle {PortForwards.linkNotice=ForwardLinkNotice("preview","Shipyard · 4321 → 4321","http://127.0.0.1:4321/")}
            ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {Surface(Modifier.fillMaxSize()) {Box(Modifier.fillMaxSize()) {ForwardLinkUi(Modifier.align(Alignment.BottomEnd))}}}}
            ui.onNodeWithContentDescription("Open forwarded link").assertIsDisplayed()
            ui.onNodeWithContentDescription("Copy forwarded link").assertIsDisplayed()
            val bounds=ui.onNodeWithContentDescription("Open forwarded link").getUnclippedBoundsInRoot()
            val open=ui.onNodeWithContentDescription("Copy forwarded link").getUnclippedBoundsInRoot()
            assertTrue("Notice fits in two compact rows",open.bottom.value-bounds.top.value<72f)
            ui.waitForIdle()
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            Thread.sleep(300)
            File(instrumentation.targetContext.cacheDir,"forward-notice.png").outputStream().use {instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
            ui.onNodeWithContentDescription("Dismiss forwarding notice").performClick()
            ui.runOnIdle {assertNull(PortForwards.linkNotice)}
        } finally {ui.runOnIdle {PortForwards.linkNotice=original}}
    }
}
