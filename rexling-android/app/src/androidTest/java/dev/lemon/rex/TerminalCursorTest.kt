package dev.lemon.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalCursorTest {
    @get:Rule val ui=createComposeRule()
    @Test fun programShapesBlinkAndSteadyModeCancelsBlinking() {
        lateinit var view: TerminalView
        val frame=TerminalScreen(20,10,0,0,-1,0,emptyList())
        ui.setContent {AndroidView(factory={TerminalView(it).also {v->view=v;v.screen=frame}},modifier=Modifier.fillMaxSize())}
        ui.waitUntil(5000) {view.hasWindowFocus() && view.width>0}
        fun pixels(): Int {
            var count=0
            ui.runOnIdle {
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val data=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(data,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                count=data.count {it==view.palette.accent};bitmap.recycle()
            }
            return count
        }
        val block=pixels();assertTrue(block>0)
        ui.runOnIdle {view.screen=frame.copy(cursorStyle=0)};val bar=pixels();assertTrue(bar in 1 until block)
        ui.runOnIdle {view.screen=frame.copy(cursorStyle=2)};val underline=pixels();assertTrue(underline in 1 until block)
        ui.runOnIdle {view.screen=frame.copy(cursorBlinking=true)}
        val phases=mutableSetOf<Int>()
        repeat(12) {phases+=pixels();SystemClock.sleep(100)}
        assertTrue("Blinking cursor must have visible and hidden phases: $phases",phases.contains(0) && phases.any {it>0})
        ui.runOnIdle {view.screen=frame.copy(cursorBlinking=false)}
        repeat(6) {SystemClock.sleep(120);assertEquals(block,pixels())}
        ui.runOnIdle {view.screen=frame.copy(cursorBlinking=true);view.interactive=false}
        repeat(6) {SystemClock.sleep(120);assertEquals(block,pixels())}
        ui.runOnIdle {view.screen=frame.copy(cursorX=null,cursorY=null)};assertEquals(0,pixels())
        ui.runOnIdle {
            view.screen=frame.copy(cursorX=null,cursorY=null,cursorAnchorX=0,cursorAnchorY=8)
            assertNull(view.keyboardFocus);view.allowHiddenCursorInput=true;assertNotNull(view.keyboardFocus)
        }
        assertEquals("An input anchor must not draw a hidden cursor",0,pixels())
    }
}
