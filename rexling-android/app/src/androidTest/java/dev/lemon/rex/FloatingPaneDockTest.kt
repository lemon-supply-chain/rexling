package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingPaneDockTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun dockOpensSwitchesAndMinimizesWithoutRemovingOtherIcons() {
        val active = mutableStateOf<String?>(null)
        val unread = mutableStateOf(mapOf("vim" to 1))
        val panes = listOf(BlockItem("vim", "Editor"), BlockItem("shell", "Logs"))
        val programs =
            mapOf(
                "vim" to ProgramIdentity(ProgramKind.NEOVIM, "nvim"),
                "shell" to ProgramIdentity.Terminal,
            )
        var minimized = 0
        var capturing = false
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(Modifier.fillMaxSize()) {
                    FloatingPaneDock(
                        panes,
                        active.value,
                        programs,
                        emptyMap(),
                        true,
                        {
                            active.value = it
                            unread.value = emptyMap()
                        },
                        {
                            active.value = null
                            minimized++
                        },
                        Modifier.align(Alignment.BottomEnd),
                        directories = mapOf("vim" to "~/Dev/rexling", "shell" to "~/Dev/logs"),
                        onExpandedChange = { capturing = it },
                        unread = unread.value,
                    )
                }
            }
        }
        ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "floating-stack-light.png",
                )
                .outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val editor =
            ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Editor"))
        val logs =
            ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Logs"))
        val dock = ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes))
        editor.assertDoesNotExist()
        ui.onNodeWithText("Editor · ~/Dev/rexling").assertDoesNotExist()
        ui.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("Floating dock unread dot", useUnmergedTree = true).assertIsDisplayed()
        dock.assertIsNotSelected()
        val anchor = dock.fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance = false
        dock.performClick()
        ui.mainClock.advanceTimeBy(96)
        val openingEditor = editor.fetchSemanticsNode().boundsInRoot
        val openingLogs = logs.fetchSemanticsNode().boundsInRoot
        assertEquals(anchor, dock.fetchSemanticsNode().boundsInRoot)
        ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "floating-dock-opening.png",
                )
                .outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        ui.mainClock.advanceTimeBy(300)
        val openEditor = editor.fetchSemanticsNode().boundsInRoot
        val openLogs = logs.fetchSemanticsNode().boundsInRoot
        assertEquals(anchor.right, openEditor.right, 1f)
        assertEquals(anchor.right, openLogs.right, 1f)
        assertTrue(
            "The rows should rise from the dock",
            openingEditor.top > openEditor.top && openingLogs.top > openLogs.top,
        )
        assertTrue(
            "The outer row should sweep in from the dock",
            openingEditor.center.x > openEditor.center.x,
        )
        assertTrue(
            "The row nearest the dock should open first",
            openingLogs.width / openLogs.width > openingEditor.width / openEditor.width,
        )
        ui.mainClock.autoAdvance = true
        ui.onNodeWithText("Editor · ~/Dev/rexling").assertIsDisplayed()
        ui.onNodeWithTag("Floating pane unread dot vim", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("Floating pane unread dot shell", useUnmergedTree = true)
            .assertDoesNotExist()
        ui.runOnIdle { assertTrue(capturing) }
        ui.onNodeWithText("Logs · ~/Dev/logs").assertIsDisplayed()
        assertEquals(anchor, dock.fetchSemanticsNode().boundsInRoot)
        editor.performClick()
        ui.onNodeWithTag("Floating dock unread dot", useUnmergedTree = true).assertDoesNotExist()
        ui.onNodeWithContentDescription(
                appString(R.string.a11y_minimize_floating_pane_named, "Editor"),
            )
            .assertDoesNotExist()
        dock.assertIsSelected()
        dock.performClick()
        ui.onNodeWithContentDescription(
                appString(R.string.a11y_minimize_floating_pane_named, "Editor"),
            )
            .assertIsSelected()
        ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "floating-dock-light.png",
                )
                .outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        logs.performClick()
        dock.performClick()
        ui.onNodeWithContentDescription(
                appString(R.string.a11y_minimize_floating_pane_named, "Logs"),
            )
            .assertIsSelected()
            .performClick()
        dock.performClick()
        editor.assertIsDisplayed()
        logs.assertIsDisplayed()
        ui.runOnIdle {
            assertNull(active.value)
            assertEquals(1, minimized)
        }
        ui.mainClock.autoAdvance = false
        dock.performClick()
        ui.mainClock.advanceTimeBy(128)
        val closingEditor = editor.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The row should shrink and move closer to the dock: $openEditor -> $closingEditor",
            closingEditor.width < openEditor.width &&
                (closingEditor.center - anchor.center).getDistance() <
                    (openEditor.center - anchor.center).getDistance(),
        )
        ui.runOnIdle { assertTrue(capturing) }
        ui.mainClock.advanceTimeBy(240)
        editor.assertDoesNotExist()
        ui.mainClock.autoAdvance = true
        ui.runOnIdle { assertFalse(capturing) }
    }

    @Test
    fun holdingAndDraggingMovesTheDockAndTheMenuOpensInsideTheTerminal() {
        val position = mutableStateOf(Offset(1f, 1f))
        val height = mutableStateOf(420.dp)
        val panes = listOf(BlockItem("one", "Editor"), BlockItem("two", "Logs"))
        var selected: String? = null
        var windowAnchor = Rect.Zero
        var indicatorColor = Color.Unspecified
        ui.setContent {
            MaterialTheme {
                indicatorColor = MaterialTheme.colorScheme.primary
                Box(Modifier.size(320.dp, height.value).testTag("Floating workspace")) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).height(paneIndicatorStripHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        PanePositionIndicator(0, 2, { 0f }, Modifier.testTag("Pane marks"))
                    }
                    FloatingPaneDock(
                        panes,
                        null,
                        emptyMap(),
                        emptyMap(),
                        true,
                        { selected = it },
                        {},
                        position = position.value,
                        onMove = { position.value = it },
                        onDockBounds = { windowAnchor = it },
                    )
                }
            }
        }
        val dock = ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes))
        val workspace = ui.onNodeWithTag("Floating workspace").fetchSemanticsNode().boundsInRoot
        val start = dock.fetchSemanticsNode().boundsInRoot.center
        val initialAnchor = ui.runOnIdle { windowAnchor.center }
        val target = workspace.topLeft + with(ui.density) { Offset(55.dp.toPx(), 42.dp.toPx()) }
        val defaultPill =
            ui.onNodeWithTag("Floating dock capsule", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val marks = ui.onNodeWithTag("Pane marks")
        val pixels = marks.captureToImage().toPixelMap()
        val lastMarkRow =
            (pixels.height - 1 downTo 0).first { y ->
                (0 until pixels.width).any { x -> pixels[x, y] == indicatorColor }
            }
        assertEquals(
            marks.fetchSemanticsNode().boundsInRoot.top +
                lastMarkRow +
                1 +
                with(ui.density) { 4.dp.toPx() },
            defaultPill.bottom,
            2f,
        )
        val activityInset = (tabRailWidth(TabRailStyle.ICONS) - activityButtonDiameter) / 2
        assertEquals(
            with(ui.density) { activityInset.toPx() },
            workspace.right - defaultPill.right,
            1f,
        )
        dock.performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(750)
        dock.performTouchInput {
            moveBy(target - start)
            up()
        }
        val moved = dock.fetchSemanticsNode().boundsInRoot
        assertEquals(target.x, moved.center.x, 2f)
        assertEquals(target.y, moved.center.y, 2f)
        ui.runOnIdle {
            assertEquals(target.x - start.x, windowAnchor.center.x - initialAnchor.x, 2f)
            assertEquals(target.y - start.y, windowAnchor.center.y - initialAnchor.y, 2f)
        }
        val pane =
            ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Editor"))
        pane.assertDoesNotExist()
        dock.performClick()
        val row = pane.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(
            row.top >= moved.bottom && row.left >= moved.left && row.right <= workspace.right,
        )
        pane.performTouchInput { click() }
        ui.runOnIdle {
            assertEquals("one", selected)
            height.value = 260.dp
        }
        val resized = ui.onNodeWithTag("Floating workspace").fetchSemanticsNode().boundsInRoot
        assertTrue(dock.fetchSemanticsNode().boundsInRoot.bottom <= resized.bottom)
    }

    @Test
    fun aLongFanBowsInwardBetweenAlignedEnds() {
        val panes =
            List(9) { i ->
                BlockItem("$i", if (i % 2 == 0) "Editor $i" else "Build and test pane $i")
            }
        ui.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 420.dp)) {
                    FloatingPaneDock(panes, null, emptyMap(), emptyMap(), true, {}, {})
                }
            }
        }
        val dock = ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes))
        val right = dock.fetchSemanticsNode().boundsInRoot.right
        dock.performClick()
        fun row(index: Int) =
            ui.onNodeWithContentDescription(
                appString(R.string.a11y_open_floating_pane, panes[index].label),
            )
        val nearest = row(8).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val lower = row(7).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val middle = row(6).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val upper = row(4).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val farthest = row(3).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(right, nearest.right, 1f)
        assertEquals(right, farthest.right, 2f)
        assertTrue(middle.right < upper.right && middle.right < lower.right)
        val bow = with(ui.density) { (nearest.right - middle.right).toDp() }
        assertTrue("The fan should have a shallow bow: $bow", bow in 18.dp..28.dp)
        val first =
            row(0)
                .assertIsNotDisplayed()
                .performScrollTo()
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(right, first.right, 1f)
    }

    @Test
    fun singleFloatingPaneOpensDirectlyAndMinimizesWithoutAMenu() {
        val active = mutableStateOf<String?>(null)
        var minimized = 0
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                Box(Modifier.fillMaxSize()) {
                    FloatingPaneDock(
                        listOf(BlockItem("one", "Editor")),
                        active.value,
                        mapOf("one" to ProgramIdentity(ProgramKind.NEOVIM, "nvim")),
                        emptyMap(),
                        true,
                        { active.value = it },
                        {
                            active.value = null
                            minimized++
                        },
                        Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes))
            .assertDoesNotExist()
        ui.onNodeWithText("1", useUnmergedTree = true).assertDoesNotExist()
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Editor"))
            .performClick()
        ui.onNodeWithContentDescription(
                appString(R.string.a11y_minimize_floating_pane_named, "Editor"),
            )
            .performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Editor"))
            .assertIsDisplayed()
        ui.runOnIdle {
            assertNull(active.value)
            assertEquals(1, minimized)
        }
    }

    @Test
    fun paneRestoresFromAndMinimizesIntoTheIconCapsule() {
        val visible = mutableStateOf(false)
        val motion = FloatingPaneMotion()
        var primary = Color.Unspecified
        var surface = Color.Unspecified
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                primary = MaterialTheme.colorScheme.primary
                surface = MaterialTheme.colorScheme.surface
                Box(
                    Modifier.fillMaxSize().onPlaced {
                        motion.workspaceOrigin = it.positionInWindow()
                    },
                ) {
                    if (visible.value)
                        Box(
                            Modifier.size(250.dp, 200.dp)
                                .floatingPaneViewport("one", motion)
                                .testTag("Restored pane"),
                        ) {
                            AndroidView(
                                factory = {
                                    TerminalView(it).apply {
                                        screen =
                                            TerminalScreen(
                                                16,
                                                9,
                                                null,
                                                null,
                                                -1,
                                                0,
                                                listOf(TerminalCell(0, 0, "Editor", -1, 0, 0, 1)),
                                            )
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                                update = { motion.trackView("one", it) },
                            )
                        }
                    MinimizingFloatingPane(motion, !visible.value)
                    FloatingPaneDock(
                        listOf(BlockItem("one", "Editor")),
                        "one".takeIf { visible.value },
                        mapOf("one" to ProgramIdentity(ProgramKind.NEOVIM, "nvim")),
                        emptyMap(),
                        true,
                        {
                            motion.restoring = it
                            visible.value = true
                        },
                        {
                            motion.capture("one")
                            visible.value = false
                        },
                        Modifier.align(Alignment.BottomEnd),
                        onDockBounds = { motion.dockBounds = it },
                    )
                }
            }
        }
        val open =
            ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Editor"))
        open.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        val pill =
            ui.onNodeWithTag("Floating dock capsule", useUnmergedTree = true)
                .assertHeightIsEqualTo(36.dp)
        val capsule = pill.fetchSemanticsNode().boundsInRoot
        val fill = pill.captureToImage().toPixelMap()
        assertEquals(surface, fill[with(ui.density) { 5.dp.roundToPx() }, fill.height / 2])
        val pane = ui.onNodeWithTag("Restored pane")
        val minimize =
            ui.onNodeWithContentDescription(
                appString(R.string.a11y_minimize_floating_pane_named, "Editor"),
            )
        ui.mainClock.autoAdvance = false
        open.performClick()
        ui.mainClock.advanceTimeBy(96)
        val restoring = pane.fetchSemanticsNode().boundsInRoot
        ui.mainClock.advanceTimeBy(300)
        val restored = pane.fetchSemanticsNode().boundsInRoot
        assertTrue(
            restoring.width in capsule.width..restored.width && restoring.width < restored.width,
        )
        assertTrue(restoring.center.y > restored.center.y)
        val pixels = pill.captureToImage().toPixelMap()
        assertEquals(primary, pixels[pixels.width / 2, 1])
        minimize.performTouchInput { click(Offset(center.x, 2.dp.toPx())) }
        ui.mainClock.advanceTimeBy(96)
        pane.assertDoesNotExist()
        val snapshot = ui.onNodeWithTag("Minimizing floating pane")
        val shrinking = snapshot.fetchSemanticsNode().boundsInRoot
        assertTrue(shrinking.width < restored.width && shrinking.center.y > restored.center.y)
        ui.mainClock.advanceTimeBy(260)
        snapshot.assertDoesNotExist()
        ui.mainClock.autoAdvance = true
        open.assertIsDisplayed()
    }

    @Test
    fun preferenceCanBeEnabledAndDisabled() {
        val enabled = mutableStateOf(false)
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                PreferencesSheet(
                    {},
                    {},
                    {},
                    {},
                    {},
                    minimizeFloatingPanes = enabled.value,
                    onMinimizeFloatingPanes = { enabled.value = it },
                )
            }
        }
        val toggle =
            ui.onNode(hasText(appString(R.string.minimize_floating_panes)) and isToggleable())
        toggle
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
            .performClick()
            .assertIsOff()
    }
}
