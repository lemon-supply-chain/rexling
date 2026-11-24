package dev.lemon.rex

import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalTypographyTest {
    @Test
    fun nerdIconsArePresentAndUseTheSameSingleCellAdvanceAsText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fonts = TerminalFonts(context)
        val paint = Paint()
        val glyphs =
            listOf(0xe0a0, 0xe0b0, 0xf07b, 0xf120, 0xe7a8, 0xf031d).map {
                String(Character.toChars(it))
            }
        for (size in listOf(8f, 10f, 12f, 14f, 16f, 18f)) {
            val grid = fonts.configure(paint, size)
            for (style in listOf(fonts.regular, fonts.bold, fonts.italic, fonts.boldItalic)) {
                paint.typeface = style
                for (glyph in glyphs) {
                    assertTrue(
                        "Missing Nerd Font glyph ${glyph.codePointAt(0).toString(16)}",
                        paint.hasGlyph(glyph),
                    )
                    assertEquals(
                        "Nerd glyph advance at $size sp",
                        grid.cellWidth,
                        paint.measureText(glyph),
                        1f,
                    )
                }
            }
        }
    }

    @Test
    fun bundledStylesKeepAsciiOnAnExactPixelGridAtEverySupportedSize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fonts = TerminalFonts(context)
        val paint = Paint()
        for (size in listOf(8f, 10f, 12f, 14f, 16f, 18f)) {
            val grid = fonts.configure(paint, size)
            assertEquals(grid.cellWidth.toInt().toFloat(), grid.cellWidth, 0f)
            assertTrue(grid.cellHeight > 0f && grid.baseline in 0f..grid.cellHeight)
            for (style in listOf(fonts.regular, fonts.bold, fonts.italic, fonts.boldItalic)) {
                paint.typeface = style
                // Hinting rounds a glyph's advance to the next pixel at small sizes; the
                // grid is still the integer cell width.
                for (code in 32..126) assertEquals(
                    "ASCII $code at $size sp",
                    grid.cellWidth,
                    paint.measureText(code.toChar().toString()),
                    1f,
                )
                assertEquals(80f * grid.cellWidth, paint.measureText("iW@0 ".repeat(16)), 80f)
            }
        }
        fonts.configure(paint, 10f)
        paint.typeface = fonts.regular
        assertTrue(paint.hasGlyph("λ"))
        assertTrue(paint.hasGlyph("│"))
        assertTrue(paint.hasGlyph("❯"))
        val oem =
            Paint().apply {
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                textSize = paint.textSize
            }
        Log.i(
            "RexTypography",
            "OEM advances M=${oem.measureText("M")} i=${oem.measureText("i")}; bundled M=${paint.measureText("M")} i=${paint.measureText("i")}",
        )
    }
}
