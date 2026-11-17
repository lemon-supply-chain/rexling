package dev.lemon.rex

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalLinksInteractionTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val url="https://example.com/docs?q=rex#android"
    private fun screen(text: String=url,columns: Int=80)=TerminalScreen(columns,4,null,null,-1,0,
        text.mapIndexed {i,c->TerminalCell(i,0,c.toString(),-1,0,0,1)})
    private fun event(view: TerminalView,action: Int,x: Float,y: Float,time: Long) {
        val e=MotionEvent.obtain(0,time,action,x,y,0);view.onTouchEvent(e);e.recycle()
    }
    @Test fun webTapOpensAViewIntentWithoutOwnershipOrInput() {
        var opened: Intent?=null;var activated=false;var typed=false
        instrumentation.runOnMainSync {
            val context=object: ContextWrapper(instrumentation.targetContext) {
                override fun startActivity(intent: Intent) {opened=intent}
            }
            val view=TerminalView(context).apply {screen=screen();onActivate={activated=true};onInput={typed=true};layout(0,0,1800,400)}
            event(view,MotionEvent.ACTION_DOWN,5f,5f,0);event(view,MotionEvent.ACTION_UP,5f,5f,30)
        }
        assertEquals(Intent.ACTION_VIEW,opened?.action);assertEquals(url,opened?.dataString)
        assertTrue(opened?.categories?.contains(Intent.CATEGORY_BROWSABLE)==true)
        assertFalse(activated);assertFalse(typed)
    }
    @Test fun dragsCancelAndLongPressNeverOpenUrls() {
        var opened=0;var activated=0
        instrumentation.runOnMainSync {
            val view=TerminalView(instrumentation.targetContext).apply {screen=screen();onOpenLink={opened++};onActivate={activated++};layout(0,0,1800,400)}
            event(view,MotionEvent.ACTION_DOWN,5f,5f,0);event(view,MotionEvent.ACTION_MOVE,300f,5f,100);event(view,MotionEvent.ACTION_UP,300f,5f,150)
            event(view,MotionEvent.ACTION_DOWN,5f,5f,200);event(view,MotionEvent.ACTION_CANCEL,5f,5f,210);event(view,MotionEvent.ACTION_UP,5f,5f,220)
            event(view,MotionEvent.ACTION_DOWN,5f,5f,300);assertTrue(view.performLongClick());event(view,MotionEvent.ACTION_UP,5f,5f,900)
            assertEquals(url,view.selectedText)
        }
        assertEquals(0,opened);assertEquals(0,activated)
    }
    @Test fun movingOutputBetweenDownAndUpDoesNotOpenADifferentLinkOrTakeControl() {
        var opened=0;var activated=0
        instrumentation.runOnMainSync {
            val view=TerminalView(instrumentation.targetContext).apply {screen=screen();onOpenLink={opened++};onActivate={activated++};layout(0,0,1800,400)}
            event(view,MotionEvent.ACTION_DOWN,5f,5f,0);view.screen=screen("https://different.example/");event(view,MotionEvent.ACTION_UP,5f,5f,30)
        }
        assertEquals(0,opened);assertEquals(0,activated)
    }
    @Test fun ordinaryTerminalTapStillActivatesAndPaddingDoesNotOpenLinks() {
        var opened=0;var activated=0
        instrumentation.runOnMainSync {
            val view=TerminalView(instrumentation.targetContext).apply {screen=screen("ordinary output");onOpenLink={opened++};onActivate={activated++};layout(0,0,1800,400)}
            event(view,MotionEvent.ACTION_DOWN,5f,5f,0);event(view,MotionEvent.ACTION_UP,5f,5f,30)
            view.setTerminalPaddingDp(12,8,6);view.screen=screen()
            event(view,MotionEvent.ACTION_DOWN,2f,2f,100);event(view,MotionEvent.ACTION_UP,2f,2f,130)
        }
        assertEquals(0,opened);assertEquals(2,activated)
    }
    @Test fun wrappedPrefixesAreNotOpenedAndRowCacheTracksContinuationChanges() {
        val opened=mutableListOf<String>()
        instrumentation.runOnMainSync {
            val base=screen(columns=url.length)
            val view=TerminalView(instrumentation.targetContext).apply {screen=base;onOpenLink={opened+=it};layout(0,0,1800,400)}
            val bitmap=Bitmap.createBitmap(1800,400,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
            view.draw(canvas)
            view.screen=base.copy(cells=base.cells+TerminalCell(0,1,"x",-1,0,0,1),lines=base.lines.mapIndexed {i,row->if(i==1) listOf(TerminalCell(0,1,"x",-1,0,0,1)) else row})
            event(view,MotionEvent.ACTION_DOWN,5f,5f,0);event(view,MotionEvent.ACTION_UP,5f,5f,30)
            assertTrue("Never open a truncated URL prefix",opened.isEmpty())
            view.draw(canvas);view.screen=base
            event(view,MotionEvent.ACTION_DOWN,5f,5f,100);event(view,MotionEvent.ACTION_UP,5f,5f,130)
            assertEquals(listOf(url),opened);bitmap.recycle()
        }
    }

    @Test fun osc8LabelAtWrappedRowBoundaryOpensStoredTargetInsteadOfVisibleText() {
        val opened=mutableListOf<String>();var activated=false
        instrumentation.runOnMainSync {
            val label="https://shown.example"
            val first=label.mapIndexed {i,c->TerminalCell(i,0,c.toString(),-1,0,0,1,"https://actual.example/docs")}
            val frame=TerminalScreen(label.length,2,null,null,-1,0,first+TerminalCell(0,1,"x",-1,0,0,1))
            val view=TerminalView(instrumentation.targetContext).apply {screen=frame;onOpenLink={opened+=it};onActivate={activated=true};layout(0,0,1800,400)}
            event(view,MotionEvent.ACTION_DOWN,5f,5f,0);event(view,MotionEvent.ACTION_UP,5f,5f,30)
        }
        assertEquals(listOf("https://actual.example/docs"),opened);assertFalse(activated)
    }

    @Test fun tappingAWrappedUrlContinuationOpensTheWholeAddress() {
        val opened=mutableListOf<String>()
        instrumentation.runOnMainSync {
            val text="https://example.com/a/long/path?q=one&next=two";val columns=12;val rows=(text.length+columns-1)/columns
            val cells=text.mapIndexed {i,c->val row=i/columns;TerminalCell(i%columns,row,c.toString(),-1,0,
                TerminalLinks.ROW_METADATA or (if(row<rows-1) TerminalLinks.WRAP else 0) or (if(row>0) TerminalLinks.CONTINUATION else 0),1)}
            val metrics=TerminalFonts(instrumentation.targetContext).configure(android.graphics.Paint(),10f)
            val view=TerminalView(instrumentation.targetContext).apply {screen=TerminalScreen(columns,rows,null,null,-1,0,cells);onOpenLink={opened+=it};layout(0,0,1800,1000)}
            val x=metrics.cellWidth*2.5f;val y=metrics.cellHeight*1.5f
            event(view,MotionEvent.ACTION_DOWN,x,y,0);event(view,MotionEvent.ACTION_UP,x,y,30)
            assertEquals(listOf(text),opened)
        }
    }

}
