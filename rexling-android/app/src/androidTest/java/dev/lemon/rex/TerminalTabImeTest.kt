package dev.lemon.rex

import android.view.Choreographer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalTabImeTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun switchingToAnUncachedTabKeepsTheKeyboardLayoutDuringLoading() =
        handoff("uncached", listOf("b"), false)

    @Test
    fun switchingToACachedTabKeepsTheKeyboardLayoutDuringOwnershipTransfer() =
        handoff("cached", listOf("b"), true)

    @Test
    fun rapidTabChangesKeepOnlyOneOutgoingEditor() = handoff("rapid", listOf("b", "c", "d"), false)

    private fun handoff(label: String, targets: List<String>, cached: Boolean) {
        var selected by mutableStateOf("a")
        var live by mutableStateOf("a")
        var controlled by mutableStateOf(true)
        var active by mutableStateOf<TerminalView?>(null)
        val frame = TerminalScreen(40, 20, 2, 18, -1, 0, emptyList())
        ui.setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .imePadding(),
                ) {
                    TerminalCarousel(
                        listOf(BlockItem(selected, "fish")),
                        selected,
                        live,
                        frame,
                        { id -> frame.takeIf { cached && id in targets } },
                        emptyMap(),
                        controlled,
                        10f,
                        {},
                        {},
                        { _, _, _, _, _ -> },
                        {},
                        {},
                        { _, _ -> },
                        {},
                        {},
                        { active = it },
                        Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                LaunchedEffect(controlled, active) { if (controlled) active?.requestFocus() }
            }
        }
        ui.runOnIdle { active!!.keyboard() }
        ui.waitUntil(7000) {
            active?.let {
                ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime())
            } == true
        }
        android.os.SystemClock.sleep(1200)
        lateinit var root: android.view.View
        ui.runOnIdle { root = active!!.rootView }
        val heights = mutableListOf<Int>()
        var sampling = true
        val sampler =
            object : Choreographer.FrameCallback {
                override fun doFrame(time: Long) {
                    heights +=
                        ViewCompat.getRootWindowInsets(root)
                            ?.getInsets(WindowInsetsCompat.Type.ime())
                            ?.bottom ?: 0
                    if (sampling) Choreographer.getInstance().postFrameCallback(this)
                }
            }
        try {
            val outgoing = active!!
            ui.runOnIdle {
                Choreographer.getInstance().postFrameCallback(sampler)
                controlled = false
            }
            for (target in targets) {
                ui.runOnIdle { selected = target }
                android.os.SystemClock.sleep(150)
                ui.runOnIdle {
                    assertFalse(
                        "The parked editor cannot keep input ownership",
                        outgoing.controlled,
                    )
                    assertNull(
                        outgoing.onCreateInputConnection(android.view.inputmethod.EditorInfo()),
                    )
                }
            }
            android.os.SystemClock.sleep(200)
            ui.runOnIdle {
                live = targets.last()
                controlled = true
            }
            android.os.SystemClock.sleep(900)
            ui.runOnIdle {
                sampling = false
                Choreographer.getInstance().removeFrameCallback(sampler)
                fun editors(view: android.view.View): Int =
                    if (view is TerminalView) 1
                    else if (view is android.view.ViewGroup)
                        (0 until view.childCount).sumOf { editors(view.getChildAt(it)) }
                    else 0
                val count = editors(root)
                val result =
                    org.json
                        .JSONObject()
                        .put("minimum_ime_height", heights.min())
                        .put("maximum_ime_height", heights.max())
                        .put("samples", heights.size)
                        .put("remaining_terminal_views", count)
                java.io
                    .File(
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                            .targetContext
                            .cacheDir,
                        "tab-ime-handoff-$label.json",
                    )
                    .writeText(result.toString(2))
                assertTrue("Keyboard sampled throughout tab replacement", heights.size > 10)
                assertTrue(
                    "No fallback keyboard/number-row layout during an uncached tab switch: $heights",
                    heights.min() > 0 && heights.max() - heights.min() <= 2,
                )
                assertTrue(active!!.hasFocus())
                assertEquals(
                    "The temporary outgoing editor must be retired after handoff",
                    1,
                    count,
                )
            }
        } finally {
            ui.runOnIdle {
                sampling = false
                Choreographer.getInstance().removeFrameCallback(sampler)
                active?.hideKeyboard()
            }
        }
    }
}
