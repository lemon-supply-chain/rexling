package dev.lemon.rex

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class MultiPaneMoveTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun paneAndSidebarGuttersAreNarrowWithWideDividerTargets() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val scope = CoroutineScope(Dispatchers.Main)
        val side = mutableStateOf(TabBarPosition.LEFT)
        val acknowledgedRatio = mutableFloatStateOf(.5f)
        val placements =
            listOf(
                PanePlacement("left", 0f, 0f, .5f, 1f),
                PanePlacement("upper", .5f, 0f, .5f, .5f),
                PanePlacement("lower", .5f, .5f, .5f, .5f),
            )
        val splits =
            listOf(
                PaneSplitLine("columns", true, .5f, 0f, 0f, 1f, 1f, "left", "upper"),
                PaneSplitLine("rows", false, .5f, .5f, 0f, .5f, 1f, "upper", "lower"),
            )
        lateinit var client: RexConnection
        ui.runOnUiThread { client = RexConnection(app, scope, "") }
        try {
            ui.setContent {
                MaterialTheme {
                    val rail = side.value
                    val (serverPanes, serverSplits) =
                        movedSplit(placements, splits, splits.first(), acknowledgedRatio.floatValue)
                    val sidebar: @androidx.compose.runtime.Composable () -> Unit = {
                        TerminalTabRail(
                            emptyList(),
                            "",
                            true,
                            {},
                            { emptyList() },
                            { 0 },
                            rail,
                            TabRailStyle.ICONS,
                            island = true,
                        ) {}
                    }
                    Row(Modifier.width(380.dp).height(360.dp)) {
                        if (rail == TabBarPosition.LEFT) sidebar()
                        Box(Modifier.weight(1f).fillMaxHeight().testTag("Tiled workspace")) {
                            MultiPaneLayout(
                                client,
                                "session",
                                "tab",
                                serverPanes,
                                focused = "",
                                liveBlock = "",
                                controlled = false,
                                programs = emptyMap(),
                                waiting = emptySet(),
                                fontSize = 11f,
                                onFocus = { _, _ -> },
                                onView = {},
                                onImage = { _, _ -> false },
                                onPaste = { false },
                                imeEnterSubmits = false,
                                keyboardChrome = { 0 },
                                extendsUnderNavigation = false,
                                hazeState = remember { HazeState() },
                                captureBackdrop = false,
                                sideRail = rail,
                                splits = serverSplits,
                            )
                        }
                        if (rail == TabBarPosition.RIGHT) sidebar()
                    }
                }
            }
            val paneBorder = with(ui.density) { 1.5.dp.roundToPx() }
            val gutter = with(ui.density) { 4.dp.toPx() }
            val outer = with(ui.density) { 8.dp.toPx() }
            for (rail in listOf(TabBarPosition.LEFT, TabBarPosition.RIGHT)) {
                ui.runOnIdle { side.value = rail }
                val tabs = ui.onNodeWithContentDescription("Tabs").fetchSemanticsNode().boundsInRoot
                val workspace =
                    ui.onNodeWithTag("Tiled workspace").fetchSemanticsNode().boundsInRoot
                val (left, upper, lower) =
                    ui.onAllNodesWithContentDescription("Focus pane").fetchSemanticsNodes().map {
                        it.boundsInRoot
                    }
                assertEquals(gutter, upper.left - left.right - 2 * paneBorder, 2f)
                assertEquals(gutter, lower.top - upper.bottom - 2 * paneBorder, 2f)
                assertEquals(outer, left.top - workspace.top - paneBorder, 2f)
                val sidebarGap =
                    if (rail == TabBarPosition.LEFT) left.left - tabs.right
                    else tabs.left - upper.right
                assertEquals(gutter, sidebarGap - paneBorder, 2f)
            }
            val columns =
                ui.onNodeWithContentDescription("Resize panes side by side")
                    .assertWidthIsEqualTo(24.dp)
            ui.onNodeWithContentDescription("Resize stacked panes").assertHeightIsEqualTo(24.dp)
            val before = columns.fetchSemanticsNode().boundsInRoot.center.x
            columns.performTouchInput {
                val from = center + Offset(8.dp.toPx(), 0f)
                swipe(from, from + Offset(32.dp.toPx(), 0f))
            }
            val first = columns.fetchSemanticsNode().boundsInRoot.center.x
            assertTrue(first > before)
            val workspace = ui.onNodeWithTag("Tiled workspace").fetchSemanticsNode().boundsInRoot
            val firstRatio = .5f + (first - before) / (workspace.width - outer)
            columns.performTouchInput {
                down(center)
                moveBy(Offset(28.dp.toPx(), 0f), delayMillis = 100)
            }
            val second = columns.fetchSemanticsNode().boundsInRoot.center.x
            assertTrue(second > first)
            ui.runOnIdle { acknowledgedRatio.floatValue = firstRatio }
            assertEquals(second, columns.fetchSemanticsNode().boundsInRoot.center.x, 2f)
            columns.performTouchInput {
                moveBy(Offset(20.dp.toPx(), 0f), delayMillis = 100)
                up()
            }
            assertTrue(columns.fetchSemanticsNode().boundsInRoot.center.x > second)
        } finally {
            ui.runOnIdle {
                client.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun aSharedWindowCallbackDisablesFullWorkspaceEvenBeforeBoundsChange() {
        lateinit var activity: ComponentActivity
        ui.setContent {
            val host = LocalActivity.current as ComponentActivity
            SideEffect { activity = host }
            Text(if (isUnfoldedFullScreen()) "Full workspace" else "Compact workspace")
        }
        assumeTrue(ui.onAllNodesWithText("Full workspace").fetchSemanticsNodes().isNotEmpty())
        ui.runOnIdle {
            activity.onMultiWindowModeChanged(true, Configuration(activity.resources.configuration))
        }
        ui.onNodeWithText("Compact workspace").assertIsDisplayed()
        ui.runOnIdle {
            activity.onMultiWindowModeChanged(
                false,
                Configuration(activity.resources.configuration),
            )
        }
        ui.onNodeWithText("Full workspace").assertIsDisplayed()
    }

    @Test
    fun floatingPaneOpensOverTheTilesAndMinimizingRestoresThePreviousPane() {
        val application =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val scope = CoroutineScope(Dispatchers.Main)
        val focused = mutableStateOf("right")
        val minimized = mutableStateOf(true)
        val savedBounds = mutableStateOf(PaneBounds(.25f, .25f, .5f, .5f))
        val adjustments = mutableListOf<PaneBounds>()
        val actions = mutableListOf<String>()
        val pane = BlockItem("floating", "Logs")
        val layout =
            listOf(
                PanePlacement("left", 0f, 0f, .5f, 1f),
                PanePlacement("right", .5f, 0f, .5f, 1f),
                PanePlacement(
                    pane.id,
                    .25f,
                    .25f,
                    .5f,
                    .5f,
                    layer = 1,
                    floating = true,
                    layerId = "layer:test",
                    layerBounds = savedBounds.value,
                ),
            )
        val window =
            WindowItem(
                "tab",
                "Project",
                listOf(BlockItem("left", "Left"), BlockItem("right", "Right"), pane),
                layout,
            )
        val navigation = FloatingPaneNavigation()
        navigation.remember("session", window, focused.value)
        lateinit var client: RexConnection
        ui.runOnUiThread { client = RexConnection(application, scope, "") }
        try {
            ui.setContent {
                MaterialTheme {
                    val selected = focused.value
                    val original = layout.last()
                    val bounds = savedBounds.value
                    val currentWindow =
                        window.copy(
                            layout =
                                layout.dropLast(1) +
                                    bounds
                                        .positionIn(original.layerBounds!!, original)
                                        .copy(layerBounds = bounds, minimized = minimized.value),
                        )
                    val overlay = currentWindow.visibleFloatingPlacements()
                    fun minimize() {
                        minimized.value = true
                        if (focused.value == pane.id)
                            focused.value = navigation.returnPane("session", window)!!
                    }
                    val haze = remember { HazeState() }
                    Box(Modifier.width(380.dp).height(320.dp).testTag("Workspace")) {
                        MultiPaneLayout(
                            client,
                            "session",
                            window.id,
                            multiPaneTiles(currentWindow, selected, allowFloatingOverlay = true)!!,
                            focused = selected,
                            liveBlock = selected,
                            controlled = true,
                            programs = emptyMap(),
                            waiting = emptySet(),
                            fontSize = 11f,
                            onFocus = { id, _ -> focused.value = id },
                            onView = {},
                            onImage = { _, _ -> false },
                            onPaste = { false },
                            imeEnterSubmits = false,
                            keyboardChrome = { 0 },
                            extendsUnderNavigation = false,
                            hazeState = haze,
                            captureBackdrop = false,
                            floatingPanes = overlay,
                            allowFloat = true,
                            onFloat = { actions += "float:$it" },
                            onDockFloating = { actions += "dock:$it" },
                            onClosePane = { actions += "close:$it" },
                            onMinimizeFloating = { minimize() },
                            onFloatingBounds = { _, next ->
                                adjustments += next
                                savedBounds.value = next
                                true
                            },
                            onSplit = { id, split -> actions += "split:$id:$split" },
                        )
                        FloatingPaneDock(
                            listOf(pane),
                            pane.id.takeIf { it == selected },
                            emptyMap(),
                            emptyMap(),
                            enabled = true,
                            onOpen = {
                                minimized.value = false
                                focused.value = it
                            },
                            onMinimize = { minimize() },
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                }
            }
            ui.onNodeWithTag("Floating terminal pane").assertDoesNotExist()
            ui.onNodeWithContentDescription("Open floating pane: Logs").performClick()
            ui.onAllNodesWithContentDescription("Focus pane").assertCountEquals(2)
            val overlay =
                ui.onNodeWithTag("Floating terminal pane")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            val workspace = ui.onNodeWithTag("Workspace").fetchSemanticsNode().boundsInRoot
            assertEquals(.5f, overlay.width / workspace.width, .025f)
            assertEquals(.5f, overlay.height / workspace.height, .025f)
            assertEquals(workspace.center.x, overlay.center.x, 2f)
            assertEquals(workspace.center.y, overlay.center.y, 2f)
            ui.onAllNodesWithContentDescription("Focus pane")[1].performClick()
            ui.runOnIdle { assertEquals("right", focused.value) }
            assertEquals(
                overlay,
                ui.onNodeWithTag("Floating terminal pane")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot,
            )
            val floatingCorner = hasAnyAncestor(hasTestTag("Floating terminal pane"))
            fun revealCorner() =
                ui.onNode(hasContentDescription("Pane actions, top left") and floatingCorner)
                    .performClick()
            revealCorner()
            ui.onNodeWithContentDescription("Move floating pane").performTouchInput {
                swipe(center, center + androidx.compose.ui.geometry.Offset(48f, 28f))
            }
            ui.runOnIdle {
                assertTrue(adjustments.single().x > .25f)
                assertEquals(.5f, adjustments.single().width, .01f)
            }
            ui.onNodeWithContentDescription("Resize floating pane").performTouchInput {
                swipe(center, center + androidx.compose.ui.geometry.Offset(64f, 52f))
            }
            ui.runOnIdle {
                assertTrue(
                    "Move and resize should each commit once: $adjustments",
                    adjustments.size == 2,
                )
                assertTrue(
                    "Resize should increase both dimensions: $adjustments",
                    adjustments.last().width > .5f && adjustments.last().height > .5f,
                )
            }
            ui.onNodeWithContentDescription("More pane actions").performClick()
            ui.onNodeWithText("Split right").performClick()
            revealCorner()
            ui.onNodeWithContentDescription("More pane actions").performClick()
            ui.onNodeWithText("Dock pane").performClick()
            revealCorner()
            ui.onNodeWithContentDescription("More pane actions").performClick()
            ui.onNodeWithText("Minimize floating pane").performClick()
            ui.onNodeWithTag("Floating terminal pane").assertDoesNotExist()
            ui.onAllNodesWithContentDescription("Pane actions, top left")[1].performClick()
            ui.onNodeWithContentDescription("More pane actions").performClick()
            ui.onNodeWithText("Float pane").performClick()
            ui.runOnIdle {
                assertEquals("right", focused.value)
                assertEquals(
                    listOf("split:floating:RIGHT", "dock:floating", "float:right"),
                    actions,
                )
            }
        } finally {
            ui.runOnIdle {
                client.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun eitherCornerRevealsTheHandleAndDropPositionChoosesSwapOrSplit() {
        val application =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val scope = CoroutineScope(Dispatchers.Main)
        val drops = mutableListOf<Triple<String, String, PaneDropPosition>>()
        val splits = mutableListOf<Pair<String, PaneSplit>>()
        lateinit var client: RexConnection
        ui.runOnUiThread { client = RexConnection(application, scope, "") }
        try {
            ui.setContent {
                MaterialTheme {
                    Box(Modifier.width(380.dp).height(320.dp)) {
                        MultiPaneLayout(
                            client,
                            "session",
                            "tab",
                            listOf(
                                PanePlacement("left", 0f, 0f, .5f, 1f),
                                PanePlacement("right", .5f, 0f, .5f, 1f),
                            ),
                            focused = "left",
                            liveBlock = "left",
                            controlled = true,
                            programs = emptyMap(),
                            waiting = emptySet(),
                            fontSize = 11f,
                            onFocus = { _, _ -> error("Dragging must preserve focus") },
                            onView = {},
                            onImage = { _, _ -> false },
                            onPaste = { false },
                            imeEnterSubmits = false,
                            keyboardChrome = { 0 },
                            extendsUnderNavigation = false,
                            hazeState = remember { HazeState() },
                            captureBackdrop = false,
                            onSplit = { id, split -> splits += id to split },
                            onDrop = { source, target, position ->
                                drops += Triple(source, target, position)
                            },
                        )
                    }
                }
            }
            ui.onAllNodesWithContentDescription("Pane actions, top left")[0].performClick()
            val grip = ui.onNodeWithContentDescription("Move pane").assertIsDisplayed()
            val zoom = ui.onNodeWithContentDescription("Zoom pane").assertIsDisplayed()
            val start = grip.fetchSemanticsNode().boundsInRoot
            assertTrue(start.right < zoom.fetchSemanticsNode().boundsInRoot.left)
            val target =
                ui.onNodeWithContentDescription("Focus pane").fetchSemanticsNode().boundsInRoot
            fun destination(x: Float, y: Float) =
                target.topLeft + Offset(target.width * x, target.height * y) - start.topLeft
            grip.performTouchInput {
                down(center)
                moveTo(destination(.5f, .9f), delayMillis = 200)
                cancel()
            }
            ui.runOnIdle { assertTrue(drops.isEmpty()) }
            val positions =
                listOf(
                    PaneDropPosition.CENTER to destination(.5f, .5f),
                    PaneDropPosition.BELOW to destination(.5f, .9f),
                    PaneDropPosition.RIGHT to destination(.9f, .5f),
                    PaneDropPosition.ABOVE to destination(.5f, .1f),
                    PaneDropPosition.LEFT to destination(.1f, .5f),
                )
            positions.forEachIndexed { index, (_, end) ->
                if (index > 0)
                    ui.onAllNodesWithContentDescription("Pane actions, top left")[0].performClick()
                grip.performTouchInput { swipe(center, end, durationMillis = 300) }
            }
            ui.runOnIdle {
                assertEquals(positions.map { Triple("left", "right", it.first) }, drops)
            }
            ui.onAllNodesWithContentDescription("Pane actions")[1].performClick()
            ui.onNodeWithContentDescription("Move pane").assertIsDisplayed()
            ui.onNodeWithContentDescription("Zoom pane").assertIsDisplayed()
            ui.onNodeWithContentDescription("Split right").assertIsDisplayed().performClick()
            ui.onAllNodesWithContentDescription("Pane actions")[1].performClick()
            ui.onNodeWithContentDescription("Split below").assertIsDisplayed().performClick()
            ui.runOnIdle {
                assertEquals(listOf("right" to PaneSplit.RIGHT, "right" to PaneSplit.BELOW), splits)
            }
        } finally {
            ui.runOnIdle {
                client.close()
                scope.cancel()
            }
        }
    }
}
