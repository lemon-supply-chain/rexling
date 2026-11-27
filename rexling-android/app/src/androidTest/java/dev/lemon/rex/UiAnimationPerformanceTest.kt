package dev.lemon.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Debug
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only, repeatable UI workloads. No network or user terminal is involved. */
@RunWith(AndroidJUnit4::class)
class UiAnimationPerformanceTest {
    @Test fun workingChrome() = runWorkingChrome(true)

    @Test fun staticChromeFloor() = runWorkingChrome(false)

    private fun runWorkingChrome(animated: Boolean) {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val programs =
            listOf(
                ProgramIdentity(ProgramKind.NEOVIM, "Neovim"),
                ProgramIdentity(ProgramKind.CODEX, "Codex"),
                ProgramIdentity(ProgramKind.CLAUDE, "Claude"),
                ProgramIdentity(ProgramKind.TERMINAL, "fish"),
            )
        val windows =
            List(4) { index ->
                WindowItem(
                    "w$index",
                    programs[index].label,
                    listOf(BlockItem("b$index", programs[index].label)),
                )
            }
        scenario.onActivity { activity ->
            activity.setContent {
                RexTheme(true, ThemeCatalog.defaults[0]) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    ) {
                        SessionHeader(
                            listOf(SessionItem("s", "preview · Offline")),
                            "s",
                            true,
                            true,
                            programs,
                            0,
                            {},
                            {},
                            {},
                            title = "Investigate UI performance",
                            working = animated,
                            directory = "~/Dev/rexling",
                            menu = {},
                        )
                        TerminalTabs(
                            windows,
                            "w0",
                            true,
                            {},
                            panePrograms = { tab ->
                                val index = tab.id.removePrefix("w").toInt()
                                if (index == 0) programs.take(3) else listOf(programs[index])
                            },
                            selectedPane = { 0 },
                            isPaneWorking = { _, _ -> true },
                            paneWorking = { true },
                            animateWorking = animated,
                            actions = {},
                        )
                    }
                }
            }
        }
        val running = AtomicBoolean(true)
        val frames = Collections.synchronizedList(mutableListOf<Long>())
        scenario.onActivity {
            val callback =
                object : Choreographer.FrameCallback {
                    var previous = 0L

                    override fun doFrame(frameTimeNanos: Long) {
                        if (previous != 0L) frames += frameTimeNanos - previous
                        previous = frameTimeNanos
                        if (running.get()) Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            Choreographer.getInstance().postFrameCallback(callback)
            Debug.startAllocCounting()
        }
        Thread.sleep(8_000)
        var allocatedBytes = 0L
        var allocatedObjects = 0L
        scenario.onActivity {
            running.set(false)
            allocatedBytes = Debug.getThreadAllocSize().toLong()
            allocatedObjects = Debug.getThreadAllocCount().toLong()
            Debug.stopAllocCounting()
        }
        val sorted = frames.sorted()
        val result =
            JSONObject()
                .put("workload", "working SessionHeader plus four TerminalTabs")
                .put("animated", animated)
                .put("frames", sorted.size)
                .put("frame_interval_median_ms", sorted[sorted.size / 2] / 1e6)
                .put("frame_interval_p95_ms", sorted[sorted.size * 95 / 100] / 1e6)
                .put("main_thread_allocated_bytes", allocatedBytes)
                .put("main_thread_allocated_objects", allocatedObjects)
        scenario.close()
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("rexlet_performance", result.toString()) },
        )
    }

    @Test
    fun tileFixtures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            for (name in listOf("neovim", "codex", "claude_code", "shell")) {
                val picture = ProgramVectorPicture.load(context.assets, name)
                for ((widthDp, heightDp) in listOf(34 to 27, 22 to 18)) {
                    val width = widthDp * density
                    val height = heightDp * density
                    val bitmap =
                        Bitmap.createBitmap(
                            kotlin.math.ceil(width).toInt(),
                            kotlin.math.ceil(height).toInt(),
                            Bitmap.Config.ARGB_8888,
                        )
                    picture.drawTile(Canvas(bitmap), width, height)
                    File(context.cacheDir, "performance-tile-$name-${widthDp}x$heightDp.png")
                        .outputStream()
                        .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun vectorTileDraw() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val samples = mutableListOf<Long>()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val picture = ProgramVectorPicture.load(context.assets, "neovim")
            val bitmap =
                Bitmap.createBitmap(
                    (34 * density).toInt() + 2,
                    (27 * density).toInt() + 2,
                    Bitmap.Config.ARGB_8888,
                )
            val canvas = Canvas(bitmap)
            repeat(30) { picture.drawTile(canvas, 34 * density, 27 * density) }
            Debug.startAllocCounting()
            val objectsBefore = Debug.getThreadAllocCount()
            val bytesBefore = Debug.getThreadAllocSize()
            repeat(140) { iteration ->
                val start = System.nanoTime()
                picture.drawTile(canvas, 34 * density, 27 * density)
                if (iteration >= 40) samples += System.nanoTime() - start
            }
            val objects = Debug.getThreadAllocCount() - objectsBefore
            val bytes = Debug.getThreadAllocSize() - bytesBefore
            Debug.stopAllocCounting()
            bitmap.recycle()
            val sorted = samples.sorted()
            val result =
                JSONObject()
                    .put("workload", "34x27dp neovim vector tile, software Canvas")
                    .put("samples", samples.size)
                    .put("median_ms", sorted[sorted.size / 2] / 1e6)
                    .put("p95_ms", sorted[sorted.size * 95 / 100] / 1e6)
                    .put("draws", 140)
                    .put("allocated_bytes_per_draw", bytes / 140.0)
                    .put("allocated_objects_per_draw", objects / 140.0)
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString("rexlet_performance", result.toString()) },
            )
        }
    }
}
