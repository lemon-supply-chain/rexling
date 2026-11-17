package dev.lemon.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TerminalSymbolFallbackTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val symbols=listOf("⏵","⏩","⏴","⏸","⏹","⏺")
    @Test fun mediaSymbolsHaveGlyphsInEveryStyleAndTheLegacyResolutionPath() {
        val context=instrumentation.targetContext
        for(composite in listOf(true,false)) {
            val fonts=TerminalFonts(context,composite);val paint=Paint()
            for(size in listOf(8f,10f,14f,20f)) {
                fonts.configure(paint,size)
                for(style in 0..3) for(symbol in symbols) {
                    paint.typeface=fonts.typefaceFor(style,symbol)
                    assertTrue("Missing $symbol in style $style, composite=$composite",paint.hasGlyph(symbol))
                }
            }
        }
    }
    @Test fun actualTerminalDrawsTheTriangleInsteadOfTofuWithoutMovingFollowingText() {
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext;val paint=Paint();val grid=TerminalFonts(context).configure(paint,20f)
            fun render(prefix: String): Bitmap {
                val text=prefix+" auto mode on"
                val cells=text.mapIndexed {i,c->TerminalCell(i,0,c.toString(),0xffffc000.toInt(),0xff242424.toInt(),0,1)}
                val view=TerminalView(context).apply {textSp=20f;screen=TerminalScreen(24,1,null,null,-1,0xff242424.toInt(),cells);layout(0,0,760,120)}
                return Bitmap.createBitmap(760,120,Bitmap.Config.ARGB_8888).also {view.draw(Canvas(it))}
            }
            val real=render("⏵⏵");val blank=render("  ")
            val missingCells=listOf(TerminalCell(0,0,String(Character.toChars(0x10ffff)),0xffffc000.toInt(),0xff242424.toInt(),0,1))
            val view=TerminalView(context).apply {textSp=20f;screen=TerminalScreen(24,1,null,null,-1,0xff242424.toInt(),missingCells);layout(0,0,760,120)}
            val missing=Bitmap.createBitmap(760,120,Bitmap.Config.ARGB_8888);view.draw(Canvas(missing))
            val w=grid.cellWidth.toInt();val h=grid.cellHeight.toInt().coerceAtMost(120)
            fun pixels(bitmap: Bitmap,x: Int,width: Int)=IntArray(width*h).also {bitmap.getPixels(it,0,width,x,0,width,h)}
            assertFalse("Play glyph must draw ink",pixels(real,0,w).contentEquals(pixels(blank,0,w)))
            assertFalse("Play glyph must differ from the missing-glyph box",pixels(real,0,w).contentEquals(pixels(missing,0,w)))
            assertArrayEquals("Following text stays on its original grid",pixels(blank,2*w,10*w),pixels(real,2*w,10*w))
            File(context.cacheDir,"terminal-symbol-fallback.png").outputStream().use {real.compress(Bitmap.CompressFormat.PNG,100,it)}
            real.recycle();blank.recycle();missing.recycle()
        }
    }
}
