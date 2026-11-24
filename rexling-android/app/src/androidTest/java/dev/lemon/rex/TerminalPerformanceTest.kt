package dev.lemon.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Trace
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Repeatable CPU renderer workload, independent of networking and user terminals. */
@RunWith(AndroidJUnit4::class)
class TerminalPerformanceTest {
    @Test
    fun changingOneRowInADenseScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val samples = mutableListOf<Long>()
        instrumentation.runOnMainSync {
            val columns = 100
            val rows = 50
            val view = TerminalView(instrumentation.targetContext)
            val bitmap = Bitmap.createBitmap(1080, 1800, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 1080, 1800)
            var cells =
                (0 until rows).flatMap { y ->
                    (0 until columns).map { x ->
                        TerminalCell(
                            x,
                            y,
                            ('!'.code + (x + y) % 90).toChar().toString(),
                            -1,
                            0xff10151d.toInt(),
                            0,
                            1,
                        )
                    }
                }
            repeat(140) { iteration ->
                val row = iteration % rows
                cells = cells.map {
                    if (it.y == row)
                        it.copy(text = ('!'.code + (it.x + iteration) % 90).toChar().toString())
                    else it
                }
                val frame =
                    TerminalScreen(
                        columns,
                        rows,
                        iteration % columns,
                        row,
                        -1,
                        0xff10151d.toInt(),
                        cells,
                    )
                val start = System.nanoTime()
                Trace.beginSection("Rexlet.renderWorkload")
                view.screen = frame
                view.draw(canvas)
                Trace.endSection()
                if (iteration >= 40) samples += System.nanoTime() - start
            }
            bitmap.recycle()
        }
        val sorted = samples.sorted()
        val result =
            JSONObject()
                .put("workload", "100x50 one row changed, software Canvas")
                .put("samples", samples.size)
                .put("median_ms", sorted[sorted.size / 2] / 1e6)
                .put("p95_ms", sorted[sorted.size * 95 / 100] / 1e6)
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("rexlet_performance", result.toString()) },
        )
    }
}
