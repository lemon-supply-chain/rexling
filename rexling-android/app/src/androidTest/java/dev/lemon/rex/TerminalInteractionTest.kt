package dev.lemon.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TerminalInteractionTest {
    @Test fun verticalHistoryDragDoesNotSwitchPaneOrType() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var lines=0;var paneDrag=false;var typed=false
        instrumentation.runOnMainSync {
            val view=TerminalView(instrumentation.targetContext).apply {
                screen=TerminalScreen(20,10,null,null,-1,0,emptyList(),TerminalScroll(100,90,10))
                onScroll={lines+=it};onPaneDrag={_,active->if(active) paneDrag=true};onInput={typed=true}
            }
            fun event(action: Int,x: Float,y: Float,time: Long) {
                val e=MotionEvent.obtain(0,time,action,x,y,0);view.onTouchEvent(e);e.recycle()
            }
            event(MotionEvent.ACTION_DOWN,50f,50f,0)
            event(MotionEvent.ACTION_MOVE,50f,250f,100)
            event(MotionEvent.ACTION_UP,50f,250f,200)
        }
        assertTrue(lines<0);assertFalse(paneDrag);assertFalse(typed)
    }
    @Test fun fullScreenAppDragRoutesWheelWithoutActivationOrTyping() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var wheels=0;var activated=false;var typed=false;var local=0
        instrumentation.runOnMainSync {
            val view=TerminalView(instrumentation.targetContext).apply {
                screen=TerminalScreen(80,24,null,null,-1,0,emptyList(),TerminalScroll(24,0,24),inputFlags=3)
                onWheel={lines,_,_,_,_->wheels+=lines};onActivate={activated=true};onInput={typed=true};onScroll={local+=it}
            }
            fun event(action: Int,y: Float,time: Long) {val e=MotionEvent.obtain(0,time,action,50f,y,0);view.onTouchEvent(e);e.recycle()}
            event(MotionEvent.ACTION_DOWN,50f,0);event(MotionEvent.ACTION_MOVE,250f,100);event(MotionEvent.ACTION_UP,250f,200)
        }
        assertTrue(wheels<0);assertEquals(0,local);assertFalse(activated);assertFalse(typed)
    }
    @Test fun onlyATapRequestsActivation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();var activations=0;var panes=0
        instrumentation.runOnMainSync {
            val view=TerminalView(instrumentation.targetContext).apply {onActivate={activations++};onPaneDragEnd={_,_->panes++}}
            fun event(action: Int,x: Float,time: Long) {val e=MotionEvent.obtain(0,time,action,x,50f,0);view.onTouchEvent(e);e.recycle()}
            event(MotionEvent.ACTION_DOWN,50f,0);event(MotionEvent.ACTION_UP,50f,20)
            event(MotionEvent.ACTION_DOWN,50f,100);event(MotionEvent.ACTION_MOVE,350f,200);event(MotionEvent.ACTION_UP,350f,300)
        }
        assertEquals(1,activations);assertEquals(1,panes)
    }
    @Test fun selectingTextDoesNotAcquireControlAndKeepsItsOriginalFrame() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();var activations=0
        instrumentation.runOnMainSync {
            val frame=TerminalScreen(4,1,null,null,-1,0,listOf(TerminalCell(0,0,"h",-1,0,0,1),TerminalCell(1,0,"i",-1,0,0,1)))
            val view=TerminalView(instrumentation.targetContext).apply {screen=frame;onActivate={activations++}}
            view.layout(0,0,400,200)
            assertTrue(view.performLongClick());assertEquals("hi",view.selectedText)
            view.screen=frame.copy(cells=listOf(TerminalCell(0,0,"x",-1,0,0,1)),lines=listOf(listOf(TerminalCell(0,0,"x",-1,0,0,1))))
            assertEquals("hi",view.selectedText);assertEquals(0,activations)
            view.interactive=false;assertNull(view.selectedText)
        }
    }
    @Test fun packagedAdaptiveLauncherIconInflatesAndDraws() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Rexling",context.applicationInfo.loadLabel(context.packageManager).toString())
        val icon=context.applicationInfo.loadIcon(context.packageManager)
        assertTrue(icon is AdaptiveIconDrawable)
        val bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
        icon.setBounds(0,0,192,192);icon.draw(Canvas(bitmap))
        var colored=0
        for(y in 0 until 192) for(x in 0 until 192) {
            val pixel=bitmap.getPixel(x,y)
            if(android.graphics.Color.alpha(pixel)>200 && (android.graphics.Color.red(pixel)>100 || android.graphics.Color.blue(pixel)>100)) colored++
        }
        assertTrue("Rex artwork missing from launcher icon",colored>1000)
        File(context.cacheDir,"rex-launcher-preview.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun launcherPortraitReachesTheBottomOfTheMaskWithoutAWhiteGap() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val icon=context.applicationInfo.loadIcon(context.packageManager) as AdaptiveIconDrawable
        val size=384;icon.setBounds(0,0,size,size)
        val rendered=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        icon.draw(Canvas(rendered))
        val mask=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        Canvas(mask).drawPath(icon.iconMask,android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {color=android.graphics.Color.WHITE})
        var checked=0;var gaps=0
        for(y in size-8 until size-2) for(x in size/2-12..size/2+12) {
            if(android.graphics.Color.alpha(mask.getPixel(x,y))>250) {
                checked++
                if(android.graphics.Color.alpha(rendered.getPixel(x,y))<200) gaps++
            }
        }
        File(context.cacheDir,"rex-portrait-launcher.png").outputStream().use {rendered.compress(Bitmap.CompressFormat.PNG,100,it)}
        assertTrue("No bottom mask pixels checked",checked>100)
        assertEquals("Gap below launcher artwork: $gaps of $checked bottom pixels",0,gaps)
    }

}
