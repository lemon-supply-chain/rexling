package dev.shulhi.rex

import kotlin.math.roundToInt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalBackgroundTest {
    @Test fun aFullScreenApplicationBackgroundHasNoCellSeamsAtFractionalOffsets() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val expected=0xff394457.toInt()
        for(offset in listOf(.25f,.5f,.75f)) {
            lateinit var result: Bitmap
            instrumentation.runOnMainSync {
                val context=instrumentation.targetContext
                val grid=TerminalFonts(context).configure(android.graphics.Paint(),10f)
                val columns=18;val rows=4
                val cells=(0 until rows).flatMap {y->(0 until columns).map {x->TerminalCell(x,y,"",-1,expected,0,1)}}
                val frame=TerminalScreen(columns,rows,null,null,-1,0xff000000.toInt(),cells)
                val view=TerminalView(context).apply {screen=frame}
                val w=(columns*grid.cellWidth).toInt();val h=(rows*grid.cellHeight).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
                view.layout(0,0,w,h)
                result=Bitmap.createBitmap(w+3,h+3,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(result);canvas.translate(offset,offset);view.draw(canvas)
            }
            val y=result.height/2
            for(x in 2 until result.width-4) assertEquals("background seam at x=$x offset=$offset",expected,result.getPixel(x,y))
        }
    }
    @Test fun paddingExtendsEachAdjacentCellInsteadOfUsingOneGlobalBackground() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val colors=listOf(0xff43546a.toInt(),0xff665344.toInt(),0xff314b39.toInt(),0xff513d62.toInt())
        lateinit var result: Bitmap
        var reported=0 to 0
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            val grid=TerminalFonts(context).configure(android.graphics.Paint(),10f)
            val density=context.resources.displayMetrics.density
            val horizontal=kotlin.math.round(6*density).toInt()
            val top=kotlin.math.round(8*density).toInt()
            val bottom=kotlin.math.round(10*density).toInt()
            val cells=(0..1).flatMap {y->(0..1).map {x->TerminalCell(x,y,"",-1,colors[y*2+x],0,1)}}
            val view=TerminalView(context).apply {
                onDimensions={columns,rows->reported=columns to rows}
                setTerminalPaddingDp(6,8,10)
                screen=TerminalScreen(2,2,null,null,-1,0,cells)
            }
            val w=(2*grid.cellWidth).toInt()+horizontal*2
            val h=(2*grid.cellHeight).toInt()+top+bottom
            view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
            view.layout(0,0,w,h)
            result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(result))
        }
        assertEquals(2 to 2,reported)
        assertEquals(colors[0],result.getPixel(1,1))
        assertEquals(colors[1],result.getPixel(result.width-2,1))
        assertEquals(colors[2],result.getPixel(1,result.height-2))
        assertEquals(colors[3],result.getPixel(result.width-2,result.height-2))
    }
    @Test fun cursorFillsItsCellInsteadOfDrawingAnOutline() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        lateinit var result: Bitmap
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            val grid=TerminalFonts(context).configure(android.graphics.Paint(),10f)
            val view=TerminalView(context).apply {screen=TerminalScreen(2,2,0,0,-1,0,listOf(TerminalCell(0,0,"",-1,0,256,1)))}
            val w=(2*grid.cellWidth).toInt();val h=(2*grid.cellHeight).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
            result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(result))
        }
        assertEquals(TerminalPalette.Dark.accent,result.getPixel(result.width/4,result.height/4))
    }
    @Test fun wideCharacterSpacersDoNotEraseTheLeadingCellsBackground() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val expected=0xff394457.toInt()
        lateinit var result: Bitmap
        var cellWidth=0f
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            val grid=TerminalFonts(context).configure(android.graphics.Paint(),10f)
            cellWidth=grid.cellWidth
            val view=TerminalView(context).apply {screen=TerminalScreen(3,1,null,null,-1,0, listOf(
                TerminalCell(0,0,"",-1,expected,0,2),TerminalCell(1,0,"",-1,0,256,0),TerminalCell(2,0,"",-1,expected,0,1)))}
            val w=(3*grid.cellWidth).toInt();val h=grid.cellHeight.toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
            view.layout(0,0,w,h)
            result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(result))
        }
        assertEquals(expected,result.getPixel((cellWidth*1.5f).toInt(),result.height/2))
    }
    @Test fun rowCacheReusesUnchangedRowsAndMatchesFreshDrawing() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            val cells=(0 until 4).flatMap {y->(0 until 20).map {x->TerminalCell(x,y,('!'.code+x).toChar().toString(),-1,0xff283442.toInt(),0,1)}}
            val initial=TerminalScreen(20,4,2,2,-1,0xff000000.toInt(),cells)
            fun view(frame: TerminalScreen)=TerminalView(context).apply {
                screen=frame;setTerminalPaddingDp(12,8,6)
                measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(220,View.MeasureSpec.EXACTLY));layout(0,0,500,220)
            }
            val cached=view(initial)
            fun draw(view: TerminalView)=Bitmap.createBitmap(500,220,Bitmap.Config.ARGB_8888).also {view.draw(Canvas(it))}
            draw(cached).recycle();val count=cached.recordedRowCount
            val changed=initial.copy(cells=cells.map {if(it.y==1) it.copy(text="Z") else it},lines=initial.lines.mapIndexed {y,row->if(y==1) row.map {it.copy(text="Z")} else row})
            cached.screen=changed
            val image=draw(cached)
            assertEquals(count+1,cached.recordedRowCount)
            val fresh=draw(view(changed))
            org.junit.Assert.assertTrue("Cached and fresh rows must render identically",image.sameAs(fresh))
            image.recycle();fresh.recycle()
        }
    }

    @Test fun wideRemoteScreensKeepFontSizeAndActiveViewsReplayDimensions() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            val red=0xffd13b42.toInt();val gray=0xff253044.toInt()
            val cells=(0 until 3).flatMap {y->(0 until 120).map {x->TerminalCell(x,y,"",-1,if(x==0 || x==119) red else gray,0,1)}}
            val view=TerminalView(context).apply {screen=TerminalScreen(120,3,null,null,-1,0,cells);setTerminalPaddingDp(12,8,6)}
            view.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.EXACTLY));view.layout(0,0,400,200)
            var dimensions=0 to 0
            view.onDimensions={x,y->dimensions=x to y}
            assertTrue("Active pane must receive requested, not fitted, column count",dimensions.first in 1..119)
            val bitmap=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            val grid=TerminalFonts(context).configure(android.graphics.Paint(),10f)
            val density=context.resources.displayMetrics.density
            val left=(12*density).roundToInt();val top=(8*density).roundToInt()
            assertEquals("A wide remote grid must not shrink the font cells",red,bitmap.getPixel(left+grid.cellWidth.toInt()-1,top+1))
            bitmap.recycle()
        }
    }

    @Test fun scrollingReusesRecordingsAndMatchesFreshDrawingIncludingLinks() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            fun frame(offset: Int): TerminalScreen {
                val cells=(0 until 4).flatMap {row->(0 until 20).map {column->
                    TerminalCell(column,row,('A'.code+offset+row).toChar().toString(),-1,
                        0xff283442.toInt()+row+offset,0,1,if(column==0) "https://example.com/${offset+row}" else null)
                }}
                return TerminalScreen(20,4,null,null,-1,0,cells,TerminalScroll(100,offset.toLong(),4))
            }
            fun view(frame: TerminalScreen)=TerminalView(context).apply {
                screen=frame;setTerminalPaddingDp(12,8,6)
                measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(220,View.MeasureSpec.EXACTLY))
                layout(0,0,500,220)
            }
            fun draw(view: TerminalView)=Bitmap.createBitmap(500,220,Bitmap.Config.ARGB_8888).also {view.draw(Canvas(it))}
            val cached=view(frame(10))
            draw(cached).recycle()
            for(offset in listOf(11,10,40)) {
                val before=cached.recordedRowCount
                val next=frame(offset);cached.screen=next
                val actual=draw(cached);val expected=draw(view(next))
                assertEquals(before+if(offset==40) 4 else 1,cached.recordedRowCount)
                assertTrue("Moved rows and links must match fresh drawing",actual.sameAs(expected))
                actual.recycle();expected.recycle()
            }
        }
    }

}
