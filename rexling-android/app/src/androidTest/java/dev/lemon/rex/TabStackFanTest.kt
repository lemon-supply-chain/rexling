package dev.lemon.rex

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabStackFanTest {
    @get:Rule val ui = createComposeRule()

    private fun items(count: Int): List<TabFanItem> {
        val commands = listOf("codex", "claude", "fish", "nvim", "zsh", "python")
        return List(count) { i ->
            val program = ProgramIdentity.resolve(listOf(commands[i % commands.size]))
            TabFanItem(
                id = "pane-$i",
                program = program,
                title = "Pane ${i + 1}",
                selected = i == 0,
            )
        }
    }

    private fun save(tag: String, name: String) {
        val bitmap = ui.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
            .outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun FloatingBottomFixture(
        barPosition: Offset,
        paneCount: Int,
        onSelected: (String) -> Unit,
    ) {
        val fanItems = remember(paneCount) { items(paneCount) }
        var fanned by remember { mutableStateOf(false) }
        var anchorBounds by remember { mutableStateOf(Rect.Zero) }
        var barBounds by remember { mutableStateOf(Rect.Zero) }

        Box(
            Modifier.size(411.dp, 640.dp)
                .background(MaterialTheme.colorScheme.background)
                .testTag("Fan bottom fixture"),
        ) {
            TabStackFan(
                visible = fanned,
                items = fanItems,
                anchorBounds = anchorBounds,
                barBounds = barBounds,
                preferredAxis = Orientation.Horizontal,
                onSelect = {
                    fanned = false
                    onSelected(it)
                },
                onDismiss = { fanned = false },
                bar = {
                    FloatingTabBar(barPosition, {}) { handle ->
                        Row(
                            Modifier.width(FloatingTabBarWidth)
                                .height(FloatingTabBarHeight)
                                .background(
                                    MaterialTheme.colorScheme.surface,
                                    RexTokens.shape.tabCell,
                                )
                                .onGloballyPositioned { barBounds = it.boundsInRoot() }
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            handle()
                            Box(
                                Modifier.height(45.dp)
                                    .width(88.dp)
                                    .onGloballyPositioned { anchorBounds = it.boundsInRoot() }
                                    .clickable { fanned = !fanned }
                                    .testTag("Active stacked tab"),
                                contentAlignment = Alignment.Center,
                            ) {
                                ProgramStack(fanItems.map { it.program }, 0, fixedSlot = true)
                            }
                        }
                    }
                },
            )
        }
    }

    @Test
    fun singleTapStackExpandsOppositeSideAndDoubleTapSelectsCard() {
        var picked = ""
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                FloatingBottomFixture(Offset(0.5f, 1f), paneCount = 5) { picked = it }
            }
        }
        val tab = ui.onNodeWithTag("Active stacked tab")
        ui.awaitDisplayed(tab)
        tab.performClick()
        ui.waitForIdle()

        val card0 = ui.onNodeWithTag("Tab fan card pane-0").assertIsDisplayed()
        val card1 = ui.onNodeWithTag("Tab fan card pane-1").assertIsDisplayed()
        val card2 = ui.onNodeWithTag("Tab fan card pane-2").assertIsDisplayed()
        ui.onNodeWithTag("Tab fan overflow pane-3").assertIsDisplayed()
        ui.onNodeWithTag("Tab fan overflow pane-4").assertIsDisplayed()

        val tabBounds = tab.fetchSemanticsNode().boundsInRoot
        val b0 = card0.fetchSemanticsNode().boundsInRoot
        val b1 = card1.fetchSemanticsNode().boundsInRoot
        val b2 = card2.fetchSemanticsNode().boundsInRoot
        val o3 = ui.onNodeWithTag("Tab fan overflow pane-3").fetchSemanticsNode().boundsInRoot

        assertTrue(b0.bottom < tabBounds.top)
        assertTrue(b1.top < b0.top)
        assertTrue(b1.top < b2.top)
        assertTrue(b0.center.x < b1.center.x)
        assertTrue(b1.center.x < b2.center.x)
        // Tightly stacked together with the right-most card (overlaps card-2 rather than detached).
        assertTrue(o3.center.x > b2.center.x)
        assertTrue(o3.left < b2.right)
        save("Fan bottom fixture", "tab-stack-fan-bottom-up.png")

        // Single tap on a non-stacked card does not select or close.
        card1.performTouchInput { click() }
        ui.mainClock.advanceTimeBy(250)
        ui.waitForIdle()
        assertEquals("", picked)
        ui.onNodeWithTag("Tab stack fan").assertIsDisplayed()

        // Tapping inside the safe zone (from the fan's top + margin down to the tab bar's bottom
        // edge) does not dismiss the fan.
        val scrim = ui.onNodeWithTag("Tab stack fan scrim")
        val scrimOrigin = scrim.fetchSemanticsNode().boundsInRoot.topLeft
        scrim.performTouchInput {
            click(Offset(b1.center.x, b1.top - 10f) - scrimOrigin)
            click(Offset(b1.center.x, (b1.bottom + tabBounds.top) / 2f) - scrimOrigin)
            click(Offset(tabBounds.right + 16f, tabBounds.center.y) - scrimOrigin)
        }
        ui.waitForIdle()
        ui.onNodeWithTag("Tab stack fan").assertIsDisplayed()

        // Single tap on the right-most stacked slot keeps 3 fanned cards (2, 3, 4) and stacks 0, 1
        // on the left.
        card2.performTouchInput { click() }
        ui.mainClock.advanceTimeBy(250)
        ui.waitForIdle()

        val card2Moved = ui.onNodeWithTag("Tab fan card pane-2").assertIsDisplayed()
        val card3 = ui.onNodeWithTag("Tab fan card pane-3").assertIsDisplayed()
        val card4 = ui.onNodeWithTag("Tab fan card pane-4").assertIsDisplayed()
        val leading0 = ui.onNodeWithTag("Tab fan leading pane-0").assertIsDisplayed()
        ui.onNodeWithTag("Tab fan leading pane-1").assertIsDisplayed()

        val b2Moved = card2Moved.fetchSemanticsNode().boundsInRoot
        val b3 = card3.fetchSemanticsNode().boundsInRoot
        val b4 = card4.fetchSemanticsNode().boundsInRoot
        val l0 = leading0.fetchSemanticsNode().boundsInRoot
        assertTrue(b2Moved.center.x < b3.center.x)
        assertTrue(b3.center.x < b4.center.x)
        assertTrue(l0.center.x < b2Moved.center.x)
        assertTrue(l0.right > b2Moved.left)
        save("Fan bottom fixture", "tab-stack-fan-paged-left-stack.png")

        // Single tap on the left-most stacked slot returns to the first 3 cards.
        card2Moved.performTouchInput { click() }
        ui.mainClock.advanceTimeBy(250)
        ui.waitForIdle()
        ui.onNodeWithTag("Tab fan card pane-0").assertIsDisplayed()
        ui.onNodeWithTag("Tab fan overflow pane-3").assertIsDisplayed()

        // Double tap selects the card (even when stacked!) and closes the fan.
        ui.onNodeWithTag("Tab fan card pane-2").performTouchInput { doubleClick() }
        ui.waitForIdle()
        assertEquals("pane-2", picked)
        ui.onNodeWithTag("Tab stack fan").assertDoesNotExist()
    }

    @Test
    fun barStaysInteractiveAboveTheFanScrim() {
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                FloatingBottomFixture(Offset(0.5f, 1f), paneCount = 4) {}
            }
        }
        val tab = ui.onNodeWithTag("Active stacked tab")
        ui.awaitDisplayed(tab)
        tab.performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("Tab stack fan").assertIsDisplayed()

        // The bar sits above the scrim, so a real tap on the active tab reaches it and closes
        // the fan instead of being swallowed by the full-size scrim.
        tab.performTouchInput { click() }
        ui.waitForIdle()
        ui.onNodeWithTag("Tab stack fan").assertDoesNotExist()
    }

    @Test
    fun topParkedFloatingBarFlipsFanDownwardIntoOpenSpace() {
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                FloatingBottomFixture(Offset(0.5f, 0f), paneCount = 4) {}
            }
        }
        val tab = ui.onNodeWithTag("Active stacked tab")
        ui.awaitDisplayed(tab)
        tab.performClick()
        ui.waitForIdle()

        val tabBounds = tab.fetchSemanticsNode().boundsInRoot
        val b0 = ui.onNodeWithTag("Tab fan card pane-0").fetchSemanticsNode().boundsInRoot
        val b1 = ui.onNodeWithTag("Tab fan card pane-1").fetchSemanticsNode().boundsInRoot
        val b2 = ui.onNodeWithTag("Tab fan card pane-2").fetchSemanticsNode().boundsInRoot
        val o3 = ui.onNodeWithTag("Tab fan overflow pane-3").fetchSemanticsNode().boundsInRoot

        assertTrue(b0.top > tabBounds.bottom)
        assertTrue(b1.bottom > b0.bottom)
        assertTrue(b1.bottom > b2.bottom)
        assertTrue(o3.center.x > b2.center.x)
        assertTrue(o3.left < b2.right)

        save("Fan bottom fixture", "tab-stack-fan-top-down.png")
    }

    @Test
    fun tappingBelowMiddleParkedBarDismissesFan() {
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                FloatingBottomFixture(Offset(0.5f, 0.6f), paneCount = 4) {}
            }
        }
        val tab = ui.onNodeWithTag("Active stacked tab")
        ui.awaitDisplayed(tab)
        tab.performClick()
        ui.waitForIdle()

        ui.onNodeWithTag("Tab stack fan scrim").performTouchInput {
            click(Offset(centerX, bottom - 4f))
        }
        ui.waitForIdle()
        ui.onNodeWithTag("Tab stack fan").assertDoesNotExist()
    }

    @Test
    fun sideRailFansIntoOpenSpaceAndPagesOppositeStacks() {
        var dismissals by mutableIntStateOf(0)
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                val fanItems = remember { items(5) }
                var fanned by remember { mutableStateOf(false) }
                var anchorBounds by remember { mutableStateOf(Rect.Zero) }
                var barBounds by remember { mutableStateOf(Rect.Zero) }

                Box(
                    Modifier.size(411.dp, 640.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("Fan rail fixture"),
                ) {
                    TabStackFan(
                        visible = fanned,
                        items = fanItems,
                        anchorBounds = anchorBounds,
                        barBounds = barBounds,
                        preferredAxis = Orientation.Vertical,
                        onSelect = { fanned = false },
                        onDismiss = {
                            fanned = false
                            dismissals++
                        },
                        bar = {
                            Column(
                                Modifier.offset { IntOffset(8.dp.roundToPx(), 240.dp.roundToPx()) }
                                    .width(48.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        RexTokens.shape.tabCell,
                                    )
                                    .onGloballyPositioned { barBounds = it.boundsInRoot() }
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier.fillMaxWidth()
                                        .height(48.dp)
                                        .onGloballyPositioned { anchorBounds = it.boundsInRoot() }
                                        .clickable { fanned = !fanned }
                                        .testTag("Rail stacked tab"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ProgramStack(fanItems.map { it.program }, 0, fixedSlot = true)
                                }
                            }
                        },
                    )
                }
            }
        }
        val tab = ui.onNodeWithTag("Rail stacked tab")
        ui.awaitDisplayed(tab)
        tab.performClick()
        ui.waitForIdle()

        val tabBounds = tab.fetchSemanticsNode().boundsInRoot
        val b0 = ui.onNodeWithTag("Tab fan card pane-0").fetchSemanticsNode().boundsInRoot
        val b1 = ui.onNodeWithTag("Tab fan card pane-1").fetchSemanticsNode().boundsInRoot
        val b2 = ui.onNodeWithTag("Tab fan card pane-2").fetchSemanticsNode().boundsInRoot
        val o3 = ui.onNodeWithTag("Tab fan overflow pane-3").fetchSemanticsNode().boundsInRoot

        assertTrue(b0.left > tabBounds.right)
        assertTrue(b1.right > b0.right)
        assertTrue(b1.right > b2.right)
        assertTrue(b0.center.y > b1.center.y)
        assertTrue(b1.center.y > b2.center.y)
        assertTrue(o3.center.y < b2.center.y)
        assertTrue(o3.bottom > b2.top)
        save("Fan rail fixture", "tab-stack-fan-rail-right.png")

        // Single tap on the top-most stacked card keeps 3 fanned cards (2, 3, 4) and stacks 0, 1 at
        // the bottom.
        ui.onNodeWithTag("Tab fan card pane-2").performTouchInput { click() }
        ui.mainClock.advanceTimeBy(250)
        ui.waitForIdle()
        val b2Moved = ui.onNodeWithTag("Tab fan card pane-2").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("Tab fan card pane-3").assertIsDisplayed()
        ui.onNodeWithTag("Tab fan card pane-4").assertIsDisplayed()
        val l0 = ui.onNodeWithTag("Tab fan leading pane-0").fetchSemanticsNode().boundsInRoot
        assertTrue(l0.center.y > b2Moved.center.y)
        assertTrue(l0.top < b2Moved.bottom)

        ui.onNodeWithTag("Tab stack fan scrim").performTouchInput { click() }
        ui.waitForIdle()
        assertEquals(1, dismissals)
        ui.onNodeWithTag("Tab stack fan").assertDoesNotExist()
    }

    @Test
    fun twelveItemFanPagesContinuouslyForwardAndBackwardWithoutFreezing() {
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                FloatingBottomFixture(Offset(0.5f, 1f), paneCount = 12) {}
            }
        }
        val tab = ui.onNodeWithTag("Active stacked tab")
        ui.awaitDisplayed(tab)
        tab.performClick()
        ui.waitForIdle()

        // Page 0 -> 3 -> 6 -> 9 by tapping the visible trailing primary/stack hit target in root
        // space.
        val expectedPages = listOf(0, 3, 6, 9)
        for (idx in 0 until expectedPages.lastIndex) {
            val start = expectedPages[idx]
            val outerTag = "Tab fan card pane-${start + 2}"
            ui.onNodeWithTag("Tab fan card pane-$start").assertIsDisplayed()
            ui.onNodeWithTag(outerTag).assertIsDisplayed()
            val outerCenter = ui.onNodeWithTag(outerTag).fetchSemanticsNode().boundsInRoot.center
            ui.onNodeWithTag("Tab stack fan").performTouchInput { click(outerCenter) }
            ui.mainClock.advanceTimeBy(250)
            ui.waitForIdle()
        }

        ui.onNodeWithTag("Tab fan card pane-9").assertIsDisplayed()
        ui.onNodeWithTag("Tab fan card pane-10").assertIsDisplayed()
        ui.onNodeWithTag("Tab fan card pane-11").assertIsDisplayed()

        // Page 9 -> 6 -> 3 -> 0 by tapping the leading outer card.
        for (start in listOf(9, 6, 3)) {
            val leadTag = "Tab fan card pane-$start"
            val leadCenter = ui.onNodeWithTag(leadTag).fetchSemanticsNode().boundsInRoot.center
            ui.onNodeWithTag("Tab stack fan").performTouchInput { click(leadCenter) }
            ui.mainClock.advanceTimeBy(250)
            ui.waitForIdle()
        }

        ui.onNodeWithTag("Tab fan card pane-0").assertIsDisplayed()
        ui.onNodeWithTag("Tab fan card pane-1").assertIsDisplayed()
        ui.onNodeWithTag("Tab fan card pane-2").assertIsDisplayed()
    }

    @Test
    fun floatingStripTogglesFanWhileDockedStripNeverDoes() {
        var floatingMode by mutableStateOf(true)
        var selectedPane by mutableStateOf("")
        val tabWindows =
            listOf(
                WindowItem(
                    id = "win-0",
                    label = "Shell",
                    blocks = listOf(BlockItem("pane-0", "codex")),
                ),
                WindowItem(
                    id = "win-1",
                    label = "Work",
                    blocks =
                        listOf(
                            BlockItem("pane-1", "codex"),
                            BlockItem("pane-2", "claude"),
                            BlockItem("pane-3", "fish"),
                        ),
                ),
            )
        val fanPrograms = items(4).map { it.program }
        val fanItems = items(4).drop(1)

        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                var fannedTabId by remember { mutableStateOf<String?>(null) }
                var anchorBounds by remember { mutableStateOf(Rect.Zero) }
                var barBounds by remember { mutableStateOf(Rect.Zero) }

                Box(
                    Modifier.size(411.dp, 640.dp).background(MaterialTheme.colorScheme.background),
                ) {
                    if (floatingMode) {
                        TabStackFan(
                            visible = fannedTabId == "win-1",
                            items = fanItems,
                            anchorBounds = anchorBounds,
                            barBounds = barBounds,
                            preferredAxis = Orientation.Horizontal,
                            onSelect = {
                                selectedPane = it
                                fannedTabId = null
                            },
                            onDismiss = { fannedTabId = null },
                            bar = {
                                FloatingTabBar(Offset(0.5f, 1f), {}) { handle ->
                                    Box(Modifier.width(FloatingTabBarWidth)) {
                                        TerminalTabs(
                                            tabWindows,
                                            selectedId = "win-1",
                                            enabled = true,
                                            onSelect = {},
                                            panePrograms = { w ->
                                                if (w.id == "win-0") listOf(fanPrograms[0])
                                                else fanItems.map { it.program }
                                            },
                                            selectedPane = { 0 },
                                            floating = true,
                                            handle = handle,
                                            onFanToggle = { id ->
                                                fannedTabId = if (fannedTabId == id) null else id
                                            },
                                            onFanDismiss = { fannedTabId = null },
                                            onFanAnchorBounds = { anchorBounds = it },
                                            onFanBarBounds = { barBounds = it },
                                            actions = { TerminalActions(true, true, {}, {}) },
                                        )
                                    }
                                }
                            },
                        )
                    } else {
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                            TerminalTabs(
                                tabWindows,
                                selectedId = "win-1",
                                enabled = true,
                                onSelect = {},
                                panePrograms = { w ->
                                    if (w.id == "win-0") listOf(fanPrograms[0])
                                    else fanItems.map { it.program }
                                },
                                selectedPane = { 0 },
                                floating = false,
                                onFanToggle = { id -> fannedTabId = id },
                                onFanDismiss = { fannedTabId = null },
                                actions = { TerminalActions(true, true, {}, {}) },
                            )
                        }
                    }
                }
            }
        }

        ui.onNode(androidx.compose.ui.test.isSelected()).performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("Tab fan card pane-1").assertIsDisplayed()
        ui.onNodeWithTag("Tab fan card pane-3").performTouchInput { doubleClick() }
        ui.waitForIdle()
        assertEquals("pane-3", selectedPane)
        ui.onNodeWithTag("Tab stack fan").assertDoesNotExist()

        floatingMode = false
        ui.waitForIdle()
        ui.onNode(androidx.compose.ui.test.isSelected()).performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("Tab stack fan").assertDoesNotExist()
    }

    @Test
    fun floatingRailTogglesFanWhileDockedRailNeverDoes() {
        var floatingMode by mutableStateOf(true)
        var toggleAttempts by mutableIntStateOf(0)
        val tabWindows =
            listOf(
                WindowItem(
                    id = "win-1",
                    label = "Work",
                    blocks =
                        listOf(
                            BlockItem("pane-0", "codex"),
                            BlockItem("pane-1", "claude"),
                            BlockItem("pane-2", "fish"),
                        ),
                ),
            )
        val fanItems = items(3)

        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                var fannedTabId by remember { mutableStateOf<String?>(null) }
                var anchorBounds by remember { mutableStateOf(Rect.Zero) }
                var barBounds by remember { mutableStateOf(Rect.Zero) }

                Box(
                    Modifier.size(411.dp, 640.dp).background(MaterialTheme.colorScheme.background),
                ) {
                    if (floatingMode) {
                        TabStackFan(
                            visible = fannedTabId == "win-1",
                            items = fanItems,
                            anchorBounds = anchorBounds,
                            barBounds = barBounds,
                            preferredAxis = Orientation.Vertical,
                            onSelect = { fannedTabId = null },
                            onDismiss = { fannedTabId = null },
                            bar = {
                                FloatingTabBar(Offset(0f, 0.5f), {}) { handle ->
                                    TerminalTabRail(
                                        tabWindows,
                                        selectedId = "win-1",
                                        enabled = true,
                                        onSelect = {},
                                        { fanItems.map { it.program } },
                                        { 0 },
                                        TabBarPosition.LEFT,
                                        TabRailStyle.ICONS,
                                        floating = true,
                                        handle = handle,
                                        onFanToggle = { id ->
                                            toggleAttempts++
                                            fannedTabId = if (fannedTabId == id) null else id
                                        },
                                        onFanDismiss = { fannedTabId = null },
                                        onFanAnchorBounds = { anchorBounds = it },
                                        onFanBarBounds = { barBounds = it },
                                    ) {
                                        TerminalActions(
                                            true,
                                            true,
                                            {},
                                            {},
                                            vertical = true,
                                            sideRail = true,
                                        )
                                    }
                                }
                            },
                        )
                    } else {
                        TerminalTabRail(
                            tabWindows,
                            selectedId = "win-1",
                            enabled = true,
                            onSelect = {},
                            { fanItems.map { it.program } },
                            { 0 },
                            TabBarPosition.LEFT,
                            TabRailStyle.ICONS,
                            floating = false,
                            onFanToggle = { id ->
                                toggleAttempts++
                                fannedTabId = id
                            },
                        ) {
                            TerminalActions(true, true, {}, {}, vertical = true, sideRail = true)
                        }
                    }
                }
            }
        }

        ui.onNodeWithTag("Tab fan card pane-0").assertDoesNotExist()
        val railTab = ui.onNodeWithTag("Floating tab rail")
        ui.awaitDisplayed(railTab)
        // Click the active stacked tab inside the floating rail.
        val beforeCount = toggleAttempts
        ui.onNode(androidx.compose.ui.test.isSelected()).performClick()
        ui.waitForIdle()
        assertEquals(beforeCount + 1, toggleAttempts)
        ui.onNodeWithTag("Tab fan card pane-0").assertIsDisplayed()

        // Switching to docked mode disables fan toggling on tap.
        floatingMode = false
        ui.waitForIdle()
        ui.onNode(androidx.compose.ui.test.isSelected()).performClick()
        ui.waitForIdle()
        assertEquals(beforeCount + 1, toggleAttempts)
        ui.onNodeWithTag("Tab stack fan").assertDoesNotExist()
    }

    @Test
    fun floatingSideRailAutoHidesAtRightEdgeOnlyWhenFanIsClosed() {
        val tabWindows =
            listOf(
                WindowItem(
                    id = "win-1",
                    label = "Work",
                    blocks =
                        listOf(
                            BlockItem("pane-0", "codex"),
                            BlockItem("pane-1", "claude"),
                            BlockItem("pane-2", "fish"),
                        ),
                ),
            )
        val fanItems = items(3)

        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                var fannedTabId by remember { mutableStateOf<String?>(null) }
                var anchorBounds by remember { mutableStateOf(Rect.Zero) }
                var barBounds by remember { mutableStateOf(Rect.Zero) }

                Box(
                    Modifier.size(411.dp, 640.dp).background(MaterialTheme.colorScheme.background),
                ) {
                    TabStackFan(
                        visible = fannedTabId == "win-1",
                        items = fanItems,
                        anchorBounds = anchorBounds,
                        barBounds = barBounds,
                        preferredAxis = Orientation.Vertical,
                        onSelect = { fannedTabId = null },
                        onDismiss = { fannedTabId = null },
                        bar = {
                            FloatingTabBar(
                                Offset(1f, 0.75f),
                                {},
                                hideAtEdges = true,
                                keepVisible = fannedTabId != null,
                                idleHideMillis = 300,
                            ) { handle ->
                                TerminalTabRail(
                                    tabWindows,
                                    selectedId = "win-1",
                                    enabled = true,
                                    onSelect = {},
                                    { fanItems.map { it.program } },
                                    { 0 },
                                    TabBarPosition.RIGHT,
                                    TabRailStyle.ICONS,
                                    floating = true,
                                    handle = handle,
                                    onFanToggle = { id ->
                                        fannedTabId = if (fannedTabId == id) null else id
                                    },
                                    onFanDismiss = { fannedTabId = null },
                                    onFanAnchorBounds = { anchorBounds = it },
                                    onFanBarBounds = { barBounds = it },
                                ) {
                                    TerminalActions(
                                        true,
                                        true,
                                        {},
                                        {},
                                        vertical = true,
                                        sideRail = true,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

        // Open the fan before the idle timer fires; keepVisible prevents hiding while picking.
        ui.onNode(androidx.compose.ui.test.isSelected()).performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("Tab fan card pane-0").assertIsDisplayed()
        Thread.sleep(450)
        ui.waitForIdle()
        ui.onNodeWithTag("Floating tab rail").assertIsDisplayed()
        ui.onNodeWithTag("Floating tab expand").assertDoesNotExist()

        // Dismiss the fan; now the right-edge rail must tuck away after idleHideMillis.
        ui.onNodeWithTag("Tab stack fan scrim").performTouchInput { click(Offset(20f, 20f)) }
        ui.waitForIdle()
        ui.waitUntil(3_000) {
            runCatching { ui.onNodeWithTag("Floating tab expand").assertIsDisplayed() }.isSuccess
        }
        ui.onNodeWithTag("Floating tab rail").assertDoesNotExist()
    }

    @Test
    fun safeZoneTapDoesNotPassThroughToTerminalBehindFan() {
        var backgroundClicks by mutableIntStateOf(0)
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(Modifier.size(411.dp, 640.dp)) {
                    Box(
                        Modifier.fillMaxSize()
                            .clickable { backgroundClicks++ }
                            .testTag("Terminal backdrop"),
                    )
                    FloatingBottomFixture(Offset(0.5f, 1f), paneCount = 4) {}
                }
            }
        }
        val tab = ui.onNodeWithTag("Active stacked tab")
        ui.awaitDisplayed(tab)
        tab.performClick()
        ui.waitForIdle()

        val tabBounds = tab.fetchSemanticsNode().boundsInRoot
        val b1 = ui.onNodeWithTag("Tab fan card pane-1").fetchSemanticsNode().boundsInRoot
        val scrim = ui.onNodeWithTag("Tab stack fan scrim")
        val scrimOrigin = scrim.fetchSemanticsNode().boundsInRoot.topLeft

        scrim.performTouchInput {
            click(Offset(b1.center.x, b1.top - 10f) - scrimOrigin)
            click(Offset(b1.center.x, (b1.bottom + tabBounds.top) / 2f) - scrimOrigin)
            click(Offset(tabBounds.right + 16f, tabBounds.center.y) - scrimOrigin)
        }
        ui.waitForIdle()
        ui.onNodeWithTag("Tab stack fan").assertIsDisplayed()
        assertEquals(0, backgroundClicks)
    }

    @Test
    fun shrinkingItemCountKeepsPagingWorking() {
        var fanItems by mutableStateOf(items(8))
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                var fanned by remember { mutableStateOf(false) }
                var anchorBounds by remember { mutableStateOf(Rect.Zero) }
                var barBounds by remember { mutableStateOf(Rect.Zero) }
                Box(Modifier.size(411.dp, 640.dp)) {
                    TabStackFan(
                        visible = fanned,
                        items = fanItems,
                        anchorBounds = anchorBounds,
                        barBounds = barBounds,
                        preferredAxis = Orientation.Horizontal,
                        onSelect = { fanned = false },
                        onDismiss = { fanned = false },
                        bar = {
                            FloatingTabBar(Offset(0.5f, 1f), {}) { handle ->
                                Row(
                                    Modifier.width(FloatingTabBarWidth)
                                        .height(FloatingTabBarHeight)
                                        .background(
                                            MaterialTheme.colorScheme.surface,
                                            RexTokens.shape.tabCell,
                                        )
                                        .onGloballyPositioned { barBounds = it.boundsInRoot() }
                                        .padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    handle()
                                    Box(
                                        Modifier.height(45.dp)
                                            .width(88.dp)
                                            .onGloballyPositioned {
                                                anchorBounds = it.boundsInRoot()
                                            }
                                            .clickable { fanned = !fanned }
                                            .testTag("Active stacked tab"),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
        val tab = ui.onNodeWithTag("Active stacked tab")
        ui.awaitDisplayed(tab)
        tab.performClick()
        ui.waitForIdle()

        // Page forward twice to reach the end (windowStart = 5 for 8 items -> cards 5, 6, 7).
        ui.onNodeWithTag("Tab fan card pane-2").performTouchInput { click() }
        ui.mainClock.advanceTimeBy(250)
        ui.waitForIdle()
        ui.onNodeWithTag("Tab fan card pane-4").performTouchInput { click() }
        ui.mainClock.advanceTimeBy(250)
        ui.waitForIdle()
        ui.onNodeWithTag("Tab fan card pane-5").assertIsDisplayed()

        // Shrink to 5 items while open; windowStart clamps to 2 (cards 2, 3, 4).
        fanItems = items(5)
        ui.waitForIdle()
        ui.onNodeWithTag("Tab fan card pane-2").assertIsDisplayed()

        // Single-tapping the leading outer card pages straight back to 0, 1, 2.
        ui.onNodeWithTag("Tab fan card pane-2").performTouchInput { click() }
        ui.mainClock.advanceTimeBy(250)
        ui.waitForIdle()
        ui.onNodeWithTag("Tab fan card pane-0").assertIsDisplayed()
        ui.onNodeWithTag("Tab fan card pane-1").assertIsDisplayed()
        ui.onNodeWithTag("Tab fan card pane-2").assertIsDisplayed()
    }

    @Test
    fun accessibilityClickSelectsOuterPrimaryCardAndLabelsScrimAndStacks() {
        var picked = ""
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                FloatingBottomFixture(Offset(0.5f, 1f), paneCount = 5) { picked = it }
            }
        }
        val tab = ui.onNodeWithTag("Active stacked tab")
        ui.awaitDisplayed(tab)
        tab.performClick()
        ui.waitForIdle()

        val scrimDesc =
            ui.onNodeWithTag("Tab stack fan scrim")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf(appString(R.string.a11y_fan_dismiss)), scrimDesc)

        val overflowDesc =
            ui.onNodeWithTag("Tab fan overflow pane-3")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf(appString(R.string.a11y_fan_next_panes)), overflowDesc)

        // Custom accessibility action pages forward on the outer stacked primary card.
        val pageAction =
            ui.onNodeWithTag("Tab fan card pane-2")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsActions.CustomActions)
                ?.firstOrNull { it.label == appString(R.string.a11y_fan_next_panes) }
        assertTrue(pageAction != null)
        ui.runOnIdle { assertTrue(pageAction!!.action()) }
        ui.waitForIdle()
        ui.onNodeWithTag("Tab fan card pane-3").assertIsDisplayed()

        // Accessibility OnClick on an outer primary card selects it directly.
        ui.onNodeWithTag("Tab fan card pane-2").performSemanticsAction(SemanticsActions.OnClick)
        ui.waitForIdle()
        assertEquals("pane-2", picked)
        ui.onNodeWithTag("Tab stack fan").assertDoesNotExist()
    }

    @Test
    fun exitAnimationUsesCachedItemsWhenSourceEmpties() {
        var fanned by mutableStateOf(false)
        var fanItems by mutableStateOf(items(4))
        ui.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides true) {
                RexTheme(false, ThemeCatalog.defaults[1]) {
                    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
                    var barBounds by remember { mutableStateOf(Rect.Zero) }
                    Box(Modifier.size(411.dp, 640.dp)) {
                        TabStackFan(
                            visible = fanned,
                            items = fanItems,
                            anchorBounds = anchorBounds,
                            barBounds = barBounds,
                            preferredAxis = Orientation.Horizontal,
                            onSelect = { fanned = false },
                            onDismiss = { fanned = false },
                            bar = {
                                Box(
                                    Modifier.align(Alignment.BottomCenter)
                                        .padding(bottom = 12.dp)
                                        .size(88.dp, 45.dp)
                                        .onGloballyPositioned {
                                            anchorBounds = it.boundsInRoot()
                                            barBounds = it.boundsInRoot()
                                        }
                                        .clickable { fanned = !fanned }
                                        .testTag("Exit fixture anchor"),
                                )
                            },
                        )
                    }
                }
            }
        }
        ui.awaitDisplayed(ui.onNodeWithTag("Exit fixture anchor"))
        fanned = true
        ui.waitForIdle()
        ui.onNodeWithTag("Tab fan card pane-0").assertIsDisplayed()

        ui.mainClock.autoAdvance = false
        try {
            fanned = false
            fanItems = emptyList()
            ui.mainClock.advanceTimeBy(32)
            // Cards must remain rendered during the exit animation even though items emptied.
            ui.onNodeWithTag("Tab fan card pane-0").assertExists()

            ui.mainClock.advanceTimeBy(400)
            ui.onNodeWithTag("Tab stack fan").assertDoesNotExist()
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    @Test
    fun selectedFanCardBorderFollowsPlateSquircleContour() {
        val threeItems =
            items(3).mapIndexed { index, item ->
                item.copy(selected = index == 1, working = index == 1)
            }
        var dark by mutableStateOf(false)
        var borderArgb = 0
        ui.setContent {
            RexTheme(dark, ThemeCatalog.defaults[if (dark) 0 else 1]) {
                borderArgb = fanSelectionBorderColor(MaterialTheme.colorScheme).toArgb()
                var fanned by remember { mutableStateOf(false) }
                var anchorBounds by remember { mutableStateOf(Rect.Zero) }
                var barBounds by remember { mutableStateOf(Rect.Zero) }

                Box(
                    Modifier.size(411.dp, 640.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("Selected border fixture"),
                ) {
                    TabStackFan(
                        visible = fanned,
                        items = threeItems,
                        anchorBounds = anchorBounds,
                        barBounds = barBounds,
                        preferredAxis = Orientation.Horizontal,
                        onSelect = { fanned = false },
                        onDismiss = { fanned = false },
                        bar = {
                            FloatingTabBar(Offset(0.5f, 1f), {}) { handle ->
                                Row(
                                    Modifier.width(FloatingTabBarWidth)
                                        .height(FloatingTabBarHeight)
                                        .background(
                                            MaterialTheme.colorScheme.surface,
                                            RexTokens.shape.tabCell,
                                        )
                                        .onGloballyPositioned { barBounds = it.boundsInRoot() }
                                        .padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    handle()
                                    Box(
                                        Modifier.height(45.dp)
                                            .width(88.dp)
                                            .onGloballyPositioned {
                                                anchorBounds = it.boundsInRoot()
                                            }
                                            .clickable { fanned = !fanned }
                                            .testTag("Active stacked tab"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        ProgramStack(
                                            threeItems.map { it.program },
                                            1,
                                            fixedSlot = true,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        val tab = ui.onNodeWithTag("Active stacked tab")
        ui.awaitDisplayed(tab)
        tab.performClick()
        ui.waitForIdle()

        fun assertSelectedBorder(mode: String) {
            ui.waitForIdle()
            val expectedArgb = borderArgb
            val cardBitmap =
                ui.onNodeWithTag("Tab fan card pane-1")
                    .assertIsDisplayed()
                    .captureToImage()
                    .asAndroidBitmap()
            save("Selected border fixture", "tab-stack-fan-selected-border-$mode.png")

            val tileW = cardBitmap.width.toFloat()
            val tileH = cardBitmap.height * (38f / 48f)
            val tileTop = (cardBitmap.height - tileH) / 2f

            fun matchesBorder(x: Int, y: Int, maxChannelDelta: Int): Boolean {
                val pixel =
                    cardBitmap.getPixel(
                        x.coerceIn(0, cardBitmap.width - 1),
                        y.coerceIn(0, cardBitmap.height - 1),
                    )
                if (Color.alpha(pixel) < 128) return false
                return abs(Color.red(pixel) - Color.red(expectedArgb)) <= maxChannelDelta &&
                    abs(Color.green(pixel) - Color.green(expectedArgb)) <= maxChannelDelta &&
                    abs(Color.blue(pixel) - Color.blue(expectedArgb)) <= maxChannelDelta
            }

            // Top straight-edge midpoint on the plate contour (61x48 artboard).
            val topEdgeX = (tileW * 0.5f).toInt()
            val topEdgeY = (tileTop + tileH * (1.23f / 48f)).toInt()
            assertTrue(
                "$mode: top straight edge must carry selection stroke",
                matchesBorder(topEdgeX, topEdgeY, maxChannelDelta = 25),
            )

            // Top-right squircle corner midpoint (55.756, 5.244) in the 61x48 artboard.
            val squircleCornerX = (tileW * (55.756f / 61f)).toInt()
            val squircleCornerY = (tileTop + tileH * (5.244f / 48f)).toInt()
            assertTrue(
                "$mode: squircle corner midpoint must carry selection stroke",
                matchesBorder(squircleCornerX, squircleCornerY, maxChannelDelta = 25),
            )

            // Legacy RoundedCornerShape(9.dp) corner midpoint must remain clear of the stroke.
            val legacyCornerX = (tileW * ((48f - 2.64f) / 48f)).toInt()
            val legacyCornerY = (tileTop + tileH * (2.64f / 38f)).toInt()
            assertFalse(
                "$mode: legacy 9dp rounded corner must not draw detached selection stroke",
                matchesBorder(legacyCornerX, legacyCornerY, maxChannelDelta = 40),
            )
            cardBitmap.recycle()
        }

        assertSelectedBorder("light")
        ui.runOnIdle { dark = true }
        assertSelectedBorder("dark")
    }
}
