package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The floating tab bar: a glass capsule that floats over the workspace without resizing it. */
@RunWith(AndroidJUnit4::class)
class FloatingTabsTest {
    @get:Rule val ui = createComposeRule()
    private val density =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    private val programs =
        listOf("codex", "claude", "fish", "nvim").map { ProgramIdentity.resolve(listOf(it)) }

    private fun windows(count: Int) =
        List(count) { i ->
            WindowItem("$i", "Session ${i + 1}", listOf(BlockItem("pane-$i", "Terminal")))
        }

    private fun save(tag: String, name: String) {
        val bitmap = ui.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
            .outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun floatingBottomBarKeepsAFixedLengthCapsule() {
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(
                    Modifier.size(411.dp, 480.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("Floating tabs fixture"),
                ) {
                    FloatingTabBar(Offset(.5f, 1f), {}) { handle ->
                        Box(Modifier.width(FloatingTabBarWidth)) {
                            TerminalTabs(
                                windows(5),
                                "0",
                                true,
                                {},
                                panePrograms = { programs },
                                selectedPane = { 0 },
                                floating = true,
                                handle = handle,
                                actions = { TerminalActions(true, true, {}, {}) },
                            )
                        }
                    }
                }
            }
        }
        ui.onNodeWithTag("Floating tab bar")
            .assertWidthIsEqualTo(FloatingTabBarWidth)
            .assertHeightIsEqualTo(FloatingTabBarHeight)
        // The capsule stays one bar: tabs scroll inside it and the actions remain reachable.
        ui.onNodeWithContentDescription(appString(R.string.a11y_tabs)).assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.a11y_tabs_and_panes)).assertIsDisplayed()
        save("Floating tabs fixture", "floating-bottom-tabs.png")
    }

    @Test
    fun floatingSideRailHugsItsTabs() {
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(
                    Modifier.size(440.dp, 600.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("Floating rail fixture"),
                ) {
                    FloatingTabBar(Offset(.5f, .5f), {}) { handle ->
                        TerminalTabRail(
                            windows(3),
                            "0",
                            true,
                            {},
                            { programs },
                            { 0 },
                            TabBarPosition.LEFT,
                            TabRailStyle.ICONS,
                            floating = true,
                            handle = handle,
                        ) {
                            TerminalActions(true, true, {}, {}, vertical = true, sideRail = true)
                        }
                    }
                }
            }
        }
        val rail = ui.onNodeWithTag("Floating tab rail").fetchSemanticsNode().boundsInRoot
        assertEquals(48f, rail.width / density, 1f)
        assertTrue(
            "The rail hugs its tabs instead of the screen: ${rail.height / density}",
            rail.height / density < 500f,
        )
        ui.onNodeWithTag("Floating tab handle").assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.a11y_tabs)).assertIsDisplayed()
        save("Floating rail fixture", "floating-side-rail.png")
    }

    @Test
    fun floatingSideRailCapsItsHeight() {
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(
                    Modifier.size(440.dp, 300.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("Floating rail fixture"),
                ) {
                    FloatingTabBar(Offset(.5f, .5f), {}) { handle ->
                        TerminalTabRail(
                            windows(14),
                            "13",
                            true,
                            {},
                            { programs },
                            { 0 },
                            TabBarPosition.RIGHT,
                            TabRailStyle.ICONS,
                            floating = true,
                            handle = handle,
                        ) {
                            TerminalActions(true, true, {}, {}, vertical = true, sideRail = true)
                        }
                    }
                }
            }
        }
        // The grip and its gap plus the margins come off the top, and the capsule stops there.
        val rail = ui.onNodeWithTag("Floating tab rail").fetchSemanticsNode().boundsInRoot
        assertEquals(284f, rail.height / density, 1f)
        ui.onNodeWithContentDescription("Session 14", substring = true).assertIsDisplayed()
        save("Floating rail fixture", "floating-side-rail-capped.png")
    }

    private fun edgeFixture(
        position: Offset,
        idle: Long,
        hideAtEdges: Boolean = true,
        barOrientation: Orientation = Orientation.Vertical,
        topOcclusion: () -> Int = { 0 },
    ) {
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(
                    Modifier.size(400.dp, 600.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("Floating edge fixture"),
                ) {
                    FloatingTabBar(
                        position,
                        {},
                        hideAtEdges = hideAtEdges,
                        barOrientation = barOrientation,
                        topOcclusion = topOcclusion,
                        idleHideMillis = idle,
                    ) { handle ->
                        Row(
                            Modifier.size(160.dp, 40.dp)
                                .background(Color(0xff3a5f8a))
                                .testTag("Bar content"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            handle()
                            Box(Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aBarParkedAtTheEdgeHidesBehindAChevron() {
        edgeFixture(Offset(0f, .5f), idle = 300)
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
        ui.waitUntil(3_000) {
            runCatching { ui.onNodeWithTag("Floating tab expand").assertIsDisplayed() }.isSuccess
        }
        ui.waitForIdle()
        ui.onNodeWithTag("Bar content").assertDoesNotExist()
        // A real touch, not the semantics action, so the hit area must be placed too.
        ui.onNodeWithTag("Floating tab expand").performTouchInput { click() }
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
    }

    @Test
    fun edgeHideDisabledNeverHides() {
        edgeFixture(Offset(0f, .5f), idle = 100, hideAtEdges = false)
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
        Thread.sleep(500)
        ui.waitForIdle()
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
        ui.onNodeWithTag("Floating tab expand").assertDoesNotExist()
    }

    @Test
    fun aBottomBarParkedAtTheBottomEdgeHidesBehindAnUpwardChevron() {
        edgeFixture(Offset(.5f, 1f), idle = 300, barOrientation = Orientation.Horizontal)
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
        ui.waitUntil(3_000) {
            runCatching { ui.onNodeWithTag("Floating tab expand").assertIsDisplayed() }.isSuccess
        }
        ui.waitForIdle()
        ui.onNodeWithTag("Bar content").assertDoesNotExist()
        val fixture = ui.onNodeWithTag("Floating edge fixture").fetchSemanticsNode().boundsInRoot
        val expand = ui.onNodeWithTag("Floating tab expand").fetchSemanticsNode().boundsInRoot
        assertEquals(fixture.bottom, expand.bottom, 1f)
        assertEquals(fixture.center.x, expand.center.x, 1f)
        assertTrue(expand.width > expand.height)
        save("Floating edge fixture", "floating-bottom-edge-hidden.png")
        ui.onNodeWithTag("Floating tab expand").performTouchInput { click() }
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
    }

    @Test
    fun aBottomBarParkedAtTheTopEdgeHidesBelowTopOcclusion() {
        val occlusionPx = (36f * density).roundToInt()
        edgeFixture(
            Offset(.5f, 0f),
            idle = 300,
            barOrientation = Orientation.Horizontal,
            topOcclusion = { occlusionPx },
        )
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
        ui.waitUntil(3_000) {
            runCatching { ui.onNodeWithTag("Floating tab expand").assertIsDisplayed() }.isSuccess
        }
        ui.waitForIdle()
        ui.onNodeWithTag("Bar content").assertDoesNotExist()
        val fixture = ui.onNodeWithTag("Floating edge fixture").fetchSemanticsNode().boundsInRoot
        val expand = ui.onNodeWithTag("Floating tab expand").fetchSemanticsNode().boundsInRoot
        assertEquals(fixture.top + occlusionPx, expand.top, 1f)
        assertEquals(fixture.center.x, expand.center.x, 1f)
        assertTrue(expand.width > expand.height)
        save("Floating edge fixture", "floating-top-edge-hidden.png")
        ui.onNodeWithTag("Floating tab expand").performTouchInput { click() }
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
    }

    @Test
    fun aBottomBarInTheMiddleNeverHidesEvenAtSideEdges() {
        edgeFixture(Offset(0f, .5f), idle = 100, barOrientation = Orientation.Horizontal)
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
        Thread.sleep(500)
        ui.waitForIdle()
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
        ui.onNodeWithTag("Floating tab expand").assertDoesNotExist()
    }

    @Test
    fun aCenteredBarNeverHides() {
        edgeFixture(Offset(.5f, .5f), idle = 100)
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
        // Let the idle timer run: a centered bar has no edge to tuck into.
        Thread.sleep(500)
        ui.waitForIdle()
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
        ui.onNodeWithTag("Floating tab expand").assertDoesNotExist()
    }

    @Test
    fun longPressGripMovesTheBarWithoutResizingTheTerminal() {
        val position = mutableStateOf(Offset(.5f, 1f))
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(Modifier.size(400.dp, 600.dp)) {
                    // Stands in for the Ghostty pane: moving the bar must never touch it.
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color(0xff101418))
                            .testTag("Terminal behind the bar"),
                    )
                    FloatingTabBar(position.value, { position.value = it }) { handle ->
                        Row(
                            Modifier.size(160.dp, 40.dp)
                                .background(Color(0xff3a5f8a))
                                .testTag("Bar content"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            handle()
                            Box(Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }
        val terminal = ui.onNodeWithTag("Terminal behind the bar").fetchSemanticsNode().boundsInRoot
        val before = ui.onNodeWithTag("Bar content").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("Floating tab handle").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(-120f, -260f))
            up()
        }
        ui.waitForIdle()
        assertTrue("The drag must persist a new position", position.value.x < .5f)
        assertTrue(position.value.y < 1f)
        val after = ui.onNodeWithTag("Bar content").fetchSemanticsNode().boundsInRoot
        assertNotEquals("The bar follows the drag", before.left, after.left, 1f)
        assertEquals(
            "Moving the bar never resizes the terminal",
            terminal,
            ui.onNodeWithTag("Terminal behind the bar").fetchSemanticsNode().boundsInRoot,
        )
        save("Floating tab overlay", "floating-tab-moved.png")
    }

    @Test
    fun narrowWorkspaceClampsFloatingBottomBarInsideMargins() {
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(
                    Modifier.size(320.dp, 480.dp).background(MaterialTheme.colorScheme.background),
                ) {
                    FloatingTabBar(Offset(.5f, 1f), {}) { handle ->
                        Box(Modifier.width(FloatingTabBarWidth)) {
                            TerminalTabs(
                                windows(5),
                                "0",
                                true,
                                {},
                                panePrograms = { programs },
                                selectedPane = { 0 },
                                floating = true,
                                handle = handle,
                                actions = { TerminalActions(true, true, {}, {}) },
                            )
                        }
                    }
                }
            }
        }
        val bar = ui.onNodeWithTag("Floating tab bar").fetchSemanticsNode().boundsInRoot
        assertEquals(304f, bar.width / density, 1f)
        assertEquals(8f, bar.left / density, 1f)
        assertEquals(312f, bar.right / density, 1f)
        ui.onNodeWithContentDescription(appString(R.string.a11y_tabs_and_panes)).assertIsDisplayed()
    }

    @Test
    fun layoutSizeChangeDuringDragDoesNotStickMovingState() {
        val position = mutableStateOf(Offset(0.5f, 0.5f))
        var wideContent by mutableStateOf(true)
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(Modifier.size(400.dp, 600.dp)) {
                    FloatingTabBar(
                        position.value,
                        { position.value = it },
                        hideAtEdges = true,
                        idleHideMillis = 300,
                    ) { handle ->
                        Row(
                            Modifier.size(if (wideContent) 160.dp else 100.dp, 40.dp)
                                .background(Color(0xff3a5f8a))
                                .testTag("Bar content"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            handle()
                            Box(Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }
        ui.onNodeWithTag("Floating tab handle").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(-260f, 0f))
        }
        wideContent = false
        ui.waitForIdle()
        ui.onNodeWithTag("Floating tab handle").performTouchInput {
            moveBy(Offset(-200f, 0f))
            up()
        }
        ui.waitForIdle()
        assertEquals(0f, position.value.x, 0.01f)
        ui.waitUntil(3_000) {
            runCatching { ui.onNodeWithTag("Floating tab expand").assertIsDisplayed() }.isSuccess
        }
    }

    @Test
    fun holdingBarPausesIdleAutoHideUntilRelease() {
        edgeFixture(Offset(0f, .5f), idle = 250)
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
        ui.onNodeWithTag("Bar content").performTouchInput { down(center) }
        Thread.sleep(450)
        ui.waitForIdle()
        ui.onNodeWithTag("Bar content").assertIsDisplayed()
        ui.onNodeWithTag("Floating tab expand").assertDoesNotExist()

        ui.onNodeWithTag("Bar content").performTouchInput { up() }
        ui.waitUntil(3_000) {
            runCatching { ui.onNodeWithTag("Floating tab expand").assertIsDisplayed() }.isSuccess
        }
    }

    @Test
    fun floatingExpandedSideRailPlacesKeyboardActionLast() {
        val keyboardLabel = appString(R.string.a11y_show_keyboard)
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(
                    Modifier.size(440.dp, 600.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("Floating expanded rail fixture"),
                ) {
                    FloatingTabBar(Offset(0f, .5f), {}) { handle ->
                        TerminalTabRail(
                            windows(3),
                            "0",
                            true,
                            {},
                            { programs },
                            { 0 },
                            TabBarPosition.LEFT,
                            TabRailStyle.TITLES,
                            onNewTab = {},
                            floating = true,
                            handle = handle,
                        ) {
                            TerminalActions(
                                true,
                                true,
                                {},
                                {},
                                keyboardLabel = keyboardLabel,
                                onKeyboard = {},
                                vertical = false,
                                sideRail = true,
                            )
                        }
                    }
                }
            }
        }
        val newTab =
            ui.onNodeWithContentDescription(appString(R.string.label_new_tab))
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val switcher =
            ui.onNodeWithContentDescription(appString(R.string.a11y_tabs_and_panes))
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val activity =
            ui.onNodeWithContentDescription(appString(R.string.activity))
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val keyboard =
            ui.onNodeWithContentDescription(keyboardLabel)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot

        assertEquals(newTab.center.y, switcher.center.y, 1f)
        assertEquals(switcher.center.y, activity.center.y, 1f)
        assertEquals(activity.center.y, keyboard.center.y, 1f)
        assertTrue(newTab.right <= switcher.left)
        assertTrue(switcher.right <= activity.left)
        assertTrue(activity.right <= keyboard.left)
        save("Floating expanded rail fixture", "floating-side-rail-expanded.png")
    }
}
