package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaneLayoutMapTest {
    @get:Rule val ui = createComposeRule()
    private val selected = mutableStateOf("b")
    private val window =
        mutableStateOf(
            WindowItem(
                "w",
                "Project",
                listOf(BlockItem("a", "fish"), BlockItem("b", "Code"), BlockItem("c", "Plan")),
                listOf(
                    PanePlacement("a", 0f, 0f, .35f, 1f),
                    PanePlacement("b", .35f, 0f, .65f, .4f),
                    PanePlacement("c", .35f, .4f, .65f, .6f),
                ),
            ),
        )
    private val programs =
        mapOf(
            "a" to ProgramIdentity.resolve(listOf("fish")),
            "b" to ProgramIdentity.resolve(listOf("codex")),
            "c" to ProgramIdentity.resolve(listOf("claude")),
        )
    private val calls = mutableListOf<String>()

    private fun show(enabled: Boolean = true, dark: Boolean = false) {
        ui.setContent {
            RexTheme(dark, ThemeCatalog.defaults[if (dark) 0 else 1]) {
                Surface {
                    Box(Modifier.width(320.dp)) {
                        PaneLayoutMap(
                            window.value,
                            selected.value,
                            programs,
                            mapOf("b" to "Code", "c" to "Plan"),
                            enabled,
                        ) {
                            calls += it
                            selected.value = it
                        }
                    }
                }
            }
        }
    }

    private fun pane(title: String) = ui.onNodeWithContentDescription("Layout pane $title")

    @Test
    fun nestedRatiosAndSelectionMatchTheWindowAndTapsUsePaneIds() {
        show()
        val a = pane("fish").fetchSemanticsNode().boundsInRoot
        val b = pane("Code").fetchSemanticsNode().boundsInRoot
        val c = pane("Plan").fetchSemanticsNode().boundsInRoot
        assertTrue(a.right < b.left)
        assertEquals(b.left, c.left, 1f)
        assertEquals(b.right, c.right, 1f)
        assertTrue(b.bottom < c.top)
        assertEquals(a.top, b.top, 1f)
        assertEquals(a.bottom, c.bottom, 1f)
        assertEquals(.35f, a.width / (a.width + b.width), .02f)
        assertEquals(.4f, b.height / (b.height + c.height), .02f)
        pane("Code").assertIsSelected()
        pane("Plan").assertIsNotSelected()
        capture("nested-light")
        pane("Plan").performClick().assertIsSelected()
        pane("Code").assertIsNotSelected()
        ui.runOnIdle { assertEquals(listOf("c"), calls) }
    }

    @Test
    fun geometryRefreshMovesTilesWithoutSwitchingTheSelectedPane() {
        show(dark = true)
        ui.runOnIdle {
            window.value =
                window.value.copy(
                    layout =
                        listOf(
                            PanePlacement("a", 0f, 0f, 1f, .5f),
                            PanePlacement("b", 0f, .5f, .5f, .5f),
                            PanePlacement("c", .5f, .5f, .5f, .5f),
                        ),
                )
        }
        val a = pane("fish").fetchSemanticsNode().boundsInRoot
        val b = pane("Code").fetchSemanticsNode().boundsInRoot
        val c = pane("Plan").fetchSemanticsNode().boundsInRoot
        assertTrue(a.bottom < b.top)
        assertEquals(b.top, c.top, 1f)
        assertTrue(b.right < c.left)
        pane("Code").assertIsSelected()
        capture("updated-dark")
        ui.runOnIdle {
            assertTrue(calls.isEmpty())
            assertEquals("b", selected.value)
        }
    }

    @Test
    fun busyMapCannotSwitchPanes() {
        show(enabled = false)
        pane("Plan").assertIsNotEnabled().performClick()
        pane("Code").assertIsSelected()
        ui.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test
    fun hidingFloatingOverlaysLetsTouchReachThePaneUnderneathWithoutChangingSelection() {
        window.value =
            window.value.copy(
                layout =
                    listOf(
                        PanePlacement("a", 0f, 0f, 1f, 1f),
                        PanePlacement("b", .1f, .1f, .8f, .8f, 1, true),
                        PanePlacement("c", .15f, .15f, .7f, .7f, 2, true),
                    ),
            )
        show(dark = true)
        capture("floating-shown-dark")
        ui.onNodeWithContentDescription("Hide floating panes").performClick()
        pane("Code").assertDoesNotExist()
        pane("Plan").assertDoesNotExist()
        ui.runOnIdle {
            assertTrue(calls.isEmpty())
            assertEquals("b", selected.value)
        }
        pane("fish").performTouchInput { click(center) }.assertIsSelected()
        ui.runOnIdle { assertEquals(listOf("a"), calls) }
        capture("floating-hidden-dark")
        ui.onNodeWithContentDescription("Show floating panes").performClick()
        pane("Plan").performTouchInput { click(center) }.assertIsSelected()
        ui.runOnIdle { assertEquals(listOf("a", "c"), calls) }
    }

    @Test
    fun hidingFloatingOverlaysRetainsUpdatedGeometryAndBusyReadOnlyInspection() {
        window.value =
            window.value.copy(
                layout =
                    listOf(
                        PanePlacement("a", 0f, 0f, 1f, 1f),
                        PanePlacement("b", .1f, .1f, .8f, .8f, 1, true),
                    ),
            )
        show(enabled = false)
        ui.onNodeWithContentDescription("Hide floating panes").performClick()
        ui.runOnIdle {
            window.value =
                window.value.copy(
                    layout =
                        listOf(
                            PanePlacement("a", 0f, 0f, .5f, 1f),
                            PanePlacement("b", .2f, .2f, .6f, .6f, 1, true),
                        ),
                )
        }
        pane("Code").assertDoesNotExist()
        pane("fish").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Show floating panes").performClick()
        pane("Code").assertIsNotEnabled()
        ui.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    private fun capture(name: String) {
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "layout-$name.png",
            )
            .outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
