package dev.lemon.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual GPU replay: software bitmap tests cannot exercise RenderNodes. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=29)
class TerminalHardwareRowsTest {
    private fun image(draw: (Canvas)->Unit): Bitmap {
        val images=AtomicReference<Image>();val ready=CountDownLatch(1)
        val callbacks=HandlerThread("row-pixels").apply {start()}
        val reader=ImageReader.newInstance(480,180,PixelFormat.RGBA_8888,3,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        reader.setOnImageAvailableListener({source->
            source.acquireLatestImage()?.let {images.getAndSet(it)?.close();ready.countDown()}
        },Handler(callbacks.looper))
        val renderer=HardwareRenderer()
        val root=RenderNode("row-test").apply {setPosition(0,0,480,180)}
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val canvas=root.beginRecording(480,180)
                try {canvas.drawColor(0xff17202d.toInt());draw(canvas)} finally {root.endRecording()}
                renderer.setSurface(reader.surface);renderer.setContentRoot(root)
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            }
            assertTrue("GPU frame must reach the image reader",ready.await(5,TimeUnit.SECONDS))
            val image=images.getAndSet(null)!!
            try {
                val buffer=image.hardwareBuffer!!
                try {
                    val hardware=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))!!
                    return try {hardware.copy(Bitmap.Config.ARGB_8888,false)} finally {hardware.recycle()}
                } finally {buffer.close()}
            } finally {image.close()}
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {renderer.destroy();root.discardDisplayList()}
            reader.close();images.getAndSet(null)?.close();callbacks.quitSafely()
        }
    }
    private fun content(canvas: Canvas,changed: Boolean=false) {
        val paint=Paint().apply {color=if(changed) 0xff385a47.toInt() else 0xff34465d.toInt()}
        canvas.drawRect(0f,0f,480f,70f,paint)
        paint.color=0xff694c41.toInt();canvas.drawRect(240f,0f,480f,70f,paint)
        canvas.save();canvas.clipRect(12f,8f,468f,60f)
        val fonts=TerminalFonts(InstrumentationRegistry.getInstrumentation().targetContext)
        fonts.configure(paint,12f);paint.color=0xffe9edf5.toInt()
        canvas.drawText(if(changed) "changed λ \ue0a0" else "glyphs λ \ue0a0",15f,45f,paint)
        paint.typeface=fonts.bold;paint.alpha=150;canvas.drawText("bold",200f,45f,paint)
        paint.alpha=255;paint.strokeWidth=2f;canvas.drawLine(12f,56f,465f,56f,paint)
        canvas.restore()
    }
    @Test fun hardwareRowsMatchPictureReplayAtFractionalOffsetsAndClips() {
        val picture=TerminalRowRecording.create(false);val retained=TerminalRowRecording.create(true)
        picture.record(480,70){content(it)};retained.record(480,70){content(it)}
        try {
            for(offset in listOf(0f,.25f,.5f,.75f)) {
                fun render(row: TerminalRowRecording): Bitmap {
                    // Destroying an offscreen renderer can discard child display lists.
                    if(!row.valid) row.record(480,70){content(it)}
                    return image {canvas->
                        canvas.save();canvas.clipRect(0f,0f,479f,178f);canvas.translate(offset,offset)
                        row.draw(canvas);canvas.translate(0f,70f);row.draw(canvas);canvas.restore()
                    }
                }
                val expected=render(picture);val actual=render(retained)
                try {
                    if(!expected.sameAs(actual)) {
                        val directory=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                        java.io.File(directory,"rows-picture.png").outputStream().use {expected.compress(Bitmap.CompressFormat.PNG,100,it)}
                        java.io.File(directory,"rows-retained.png").outputStream().use {actual.compress(Bitmap.CompressFormat.PNG,100,it)}
                    }
                    assertTrue("Retained rows must preserve pixels at offset=$offset",expected.sameAs(actual))
                }
                finally {expected.recycle();actual.recycle()}
            }
        } finally {picture.release();retained.release()}
    }
    @Test fun changedAndDiscardedDisplayListsCanBeRecordedAgainWithoutStalePixels() {
        val retained=TerminalRowRecording.create(true);val picture=TerminalRowRecording.create(false)
        try {
            retained.record(480,70){content(it)}
            image {retained.draw(it)}.recycle()
            retained.record(480,70){content(it,true)}
            picture.record(480,70){content(it,true)}
            val updated=image {retained.draw(it)};val expected=image {picture.draw(it)}
            try {assertTrue("Re-recording must replace the previous row",expected.sameAs(updated))}
            finally {updated.recycle();expected.recycle()}
            retained.release();assertFalse(retained.valid)
            retained.record(480,70){content(it)};assertTrue(retained.valid)
            picture.record(480,70){content(it)}
            val restored=image {retained.draw(it)};val original=image {picture.draw(it)}
            try {assertTrue("A discarded row must recover on redraw",restored.sameAs(original))}
            finally {restored.recycle();original.recycle()}
        } finally {retained.release();picture.release()}
    }
    @Test fun hardwareTerminalCacheUpdatesOneRowAndMatchesAFreshView() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cells=(0 until 4).flatMap {y->(0 until 20).map {x->TerminalCell(x,y,('!'.code+x).toChar().toString(),-1,0xff283442.toInt(),0,1)}}
        val initial=TerminalScreen(20,4,2,2,-1,0xff000000.toInt(),cells)
        fun view(frame: TerminalScreen): TerminalView {
            lateinit var view: TerminalView
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                view=TerminalView(context).apply {
                    screen=frame;setTerminalPaddingDp(12,8,6)
                    measure(android.view.View.MeasureSpec.makeMeasureSpec(480,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(180,android.view.View.MeasureSpec.EXACTLY))
                    layout(0,0,480,180)
                }
            }
            return view
        }
        val cached=view(initial)
        image {cached.draw(it)}.recycle()
        val count=cached.recordedRowCount
        val next=initial.copy(cells=cells.map {if(it.y==1) it.copy(text="Z",background=TerminalPalette.Dark.background) else it},lines=initial.lines.mapIndexed {y,row->if(y==1) row.map {it.copy(text="Z")} else row})
        InstrumentationRegistry.getInstrumentation().runOnMainSync {cached.screen=next}
        val actual=image {cached.draw(it)}
        // Offscreen context disposal can evict untouched rows; the real view must
        // re-record those invalid lists instead of displaying blank text.
        assertTrue(cached.recordedRowCount>=count+1)
        val fresh=view(next)
        val expected=image {fresh.draw(it)}
        try {assertTrue("Hardware cached terminal must match fresh rendering",actual.sameAs(expected))}
        finally {actual.recycle();expected.recycle()}
    }

}
