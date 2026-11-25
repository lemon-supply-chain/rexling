package dev.lemon.rex

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
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
class AdaptiveTabsTest {
    @get:Rule val ui = createComposeRule()
    private val count = mutableIntStateOf(4)
    private val selected = mutableStateOf("0")
    private val width = mutableFloatStateOf(360f)
    private val appWidth = mutableIntStateOf(360)
    private val names =
        mutableStateOf(
            listOf(
                "Investigate Rex protocol",
                "Claude",
                "fish",
                "nvim",
                "Five",
                "Six",
                "Seven",
                "Eight",
            ),
        )
    private val stacked = mutableStateOf(false)
    private val programs =
        listOf("codex", "claude", "fish", "nvim").map { ProgramIdentity.resolve(listOf(it)) }

    private fun show(
        dark: Boolean = false,
        programOrder: List<ProgramIdentity> = programs,
        working: Boolean = false,
        progress: TerminalProgress? = null,
        onNewTab: (() -> Unit)? = null,
    ) {
        ui.setContent {
            val deviceConfiguration = LocalConfiguration.current
            val windowWidth = appWidth.intValue
            val configuration =
                remember(deviceConfiguration, windowWidth) {
                    Configuration(deviceConfiguration).apply { screenWidthDp = windowWidth }
                }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                RexTheme(dark, ThemeCatalog.defaults[if (dark) 0 else 1], motionEnabled = true) {
                    Box(
                        Modifier.width(width.floatValue.dp)
                            .background(MaterialTheme.colorScheme.surface)
                            .testTag("Test dock"),
                    ) {
                        TerminalTabs(
                            List(count.intValue) {
                                WindowItem(
                                    it.toString(),
                                    names.value[it],
                                    listOf(BlockItem("pane-$it", "Terminal")),
                                )
                            },
                            selected.value,
                            true,
                            onSelect = { selected.value = it },
                            panePrograms = { tab ->
                                val i = tab.id.toInt()
                                List(if (i == 0 && stacked.value) 3 else 1) { offset ->
                                    programOrder[(i + offset) % programOrder.size]
                                }
                            },
                            selectedPane = { 0 },
                            paneWorking = { working },
                            paneProgress = { if (it.id == "0") progress else null },
                            onNewTab = onNewTab,
                            actions = { TerminalActions(true, true, {}, {}) },
                        )
                    }
                }
            }
        }
    }

    private fun tab(index: Int) =
        ui.onNode(
            hasContentDescription(
                appString(R.string.a11y_tab_item, names.value[index], "").trimEnd(),
                substring = true,
            ),
        )

    private fun bounds(index: Int) = tab(index).fetchSemanticsNode().boundsInRoot

    private fun assertInside(index: Int) {
        val strip =
            ui.onNodeWithContentDescription(appString(R.string.a11y_tabs))
                .fetchSemanticsNode()
                .boundsInRoot
        val tab = bounds(index)
        assertTrue(
            "$tab outside $strip",
            tab.left >= strip.left - .5f && tab.right <= strip.right + .5f,
        )
    }

    @Test
    fun newTabButtonLeadsTheBottomTabsAtMediumAndWideWidths() {
        count.intValue = 2
        appWidth.intValue = 439
        var created = 0
        show(onNewTab = { created++ })
        val newTab = ui.onNodeWithContentDescription(appString(R.string.label_new_tab))
        newTab.assertDoesNotExist()
        ui.runOnIdle { appWidth.intValue = 440 }
        newTab.assertIsDisplayed()
        val buttonBounds = newTab.fetchSemanticsNode().boundsInRoot
        val tabBounds = bounds(0)
        val markerHeight = with(ui.density) { 3.dp.roundToPx() }
        assertTrue(buttonBounds.right <= tabBounds.left)
        assertEquals(
            (tabBounds.top + tabBounds.bottom - markerHeight) / 2f,
            buttonBounds.center.y,
            1f,
        )
        newTab.performClick()
        ui.runOnIdle {
            assertEquals(1, created)
            appWidth.intValue = 760
        }
        newTab.assertIsDisplayed()
        ui.runOnIdle { appWidth.intValue = 439 }
        newTab.assertDoesNotExist()
    }

    @Test
    fun fourTabsFitAlongsideActionsAndKeepUsefulTouchTargets() {
        show()
        repeat(4) {
            tab(it).assertIsDisplayed()
            assertInside(it)
        }
        assertTrue(bounds(0).width > bounds(1).width)
        val density =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .resources
                .displayMetrics
                .density
        repeat(4) {
            assertTrue("Tab $it width: ${bounds(it)}", bounds(it).width >= 48 * density - .5f)
            assertTrue("Tab $it height: ${bounds(it)}", bounds(it).height >= 44 * density - .5f)
        }
        ui.onNodeWithText("Claude").assertDoesNotExist()
        val dock = ui.onNodeWithTag("Test dock").fetchSemanticsNode().boundsInRoot
        val eye =
            ui.onNodeWithContentDescription(appString(R.string.activity))
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(eye.right <= dock.right)
        capture("four-tabs-light")
    }

    @Test
    fun inactiveTitlesReturnWhenThereIsRoom() {
        count.intValue = 2
        show(dark = true)
        ui.onNodeWithText("Claude").assertIsDisplayed()
        capture("two-tabs-dark")
        ui.runOnIdle { count.intValue = 4 }
        ui.onNodeWithText("Claude").assertDoesNotExist()
        repeat(4) { assertInside(it) }
        ui.runOnIdle { count.intValue = 2 }
        ui.onNodeWithText("Claude").assertIsDisplayed()
    }

    @Test
    fun longAnimatedTitlesStayAtTheirWidthCap() {
        count.intValue = 3
        show()
        val before = List(3) { bounds(it) }
        for (title in
            listOf(
                "Investigate Rex protocol ·",
                "Investigate Rex protocol ··",
                "Investigate Rex protocol with a much longer task title",
            )) {
            ui.runOnIdle { names.value = names.value.toMutableList().apply { set(0, title) } }
            repeat(3) { assertEquals(before[it], bounds(it)) }
        }
    }

    @Test
    fun stackedIconsDoNotMoveTheActiveTitle() {
        count.intValue = 2
        show()
        val before = ui.onNodeWithText(names.value[0]).fetchSemanticsNode().boundsInRoot
        ui.runOnIdle { stacked.value = true }
        assertEquals(before, ui.onNodeWithText(names.value[0]).fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun bottomTabIconAndTitleAreCenteredAboveTheMarker() {
        count.intValue = 2
        show()
        val tab = bounds(0)
        val icon =
            ui.onNodeWithTag("Tab program icon 0", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val title =
            ui.onNodeWithText(names.value[0], useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(tab.center.x, (icon.left + title.right) / 2f, 1f)
        assertEquals(icon.center.y, title.center.y, 1f)
        val markerSpace = with(ui.density) { 8.dp.roundToPx() }
        assertEquals((tab.top + tab.bottom - markerSpace) / 2f, icon.center.y, 1f)
        for (isStacked in listOf(false, true)) {
            ui.runOnIdle { stacked.value = isStacked }
            val pixels = tab(0).captureToImage().toPixelMap()
            val y = pixels.height - with(ui.density) { 6.5.dp.roundToPx() }
            val markerColor = pixels[pixels.width / 2, y]
            val markerColumns = (0 until pixels.width).filter { pixels[it, y] == markerColor }
            val leadingIconPadding = with(ui.density) { (if (isStacked) 3.5.dp else 7.dp).toPx() }
            val contentCenter = (icon.left + leadingIconPadding + title.right) / 2f - tab.left
            assertEquals(
                contentCenter,
                (markerColumns.first() + markerColumns.last() + 1) / 2f,
                1.5f,
            )
        }
    }

    @Test
    fun selectingAnOverflowTabBringsItsEntireExpandedRowIntoView() {
        count.intValue = 8
        show()
        ui.onNodeWithContentDescription(appString(R.string.a11y_tabs)).performScrollToIndex(7)
        tab(7).performClick().assertIsSelected()
        assertInside(7)
        capture("overflow-selection")
        ui.runOnIdle { selected.value = "0" }
        tab(0).assertIsSelected()
        assertInside(0)
    }

    @Test
    fun shrinkingTheViewportKeepsTheActiveTabVisible() {
        count.intValue = 8
        selected.value = "7"
        width.floatValue = 400f
        show()
        tab(7).assertIsSelected()
        assertInside(7)
        ui.runOnIdle { width.floatValue = 300f }
        tab(7).assertIsSelected()
        assertInside(7)
    }

    @Test
    fun shortActiveTitlesGiveTheirUnusedSpaceToOtherLabels() {
        count.intValue = 3
        names.value = listOf("nvim", "Investigate protocol", "Review changes")
        show(programOrder = listOf(programs[3], programs[0], programs[0]))
        val density =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .resources
                .displayMetrics
                .density
        assertTrue(bounds(0).width < 90 * density)
        ui.onNodeWithText("Investigate protocol").assertIsDisplayed()
        ui.onNodeWithText("Review changes").assertIsDisplayed()
        repeat(3) { assertInside(it) }
        capture("short-active-title")
    }

    @Test
    fun shortTitlesUseContentWidthWithoutStretching() {
        count.intValue = 3
        names.value = listOf("nvim", "vi", "sh")
        show()
        val density =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .resources
                .displayMetrics
                .density
        repeat(3) { assertTrue(bounds(it).width < 100 * density) }
        ui.onNodeWithText("vi").assertIsDisplayed()
        ui.onNodeWithText("sh").assertIsDisplayed()
    }

    @Test
    fun captionsFollowTheActiveTabLikeAnAccordion() {
        count.intValue = 3
        selected.value = "2"
        width.floatValue = 400f
        names.value =
            listOf(
                "Distant Codex task with a longer title",
                "Adjacent Codex task",
                "Identify the image | rexlink",
            )
        show(programOrder = List(3) { programs[0] })
        ui.onNodeWithText(names.value[0]).assertDoesNotExist()
        ui.onNodeWithText(names.value[1]).assertIsDisplayed()
        tab(2).assertIsSelected()
        repeat(3) { assertInside(it) }
        capture("accordion-right")
        tab(0).performClick().assertIsSelected()
        ui.onNodeWithText(names.value[1]).assertIsDisplayed()
        ui.onNodeWithText(names.value[2]).assertDoesNotExist()
        repeat(3) { assertInside(it) }
        capture("accordion-left")
    }

    @Test
    fun theBottomWorkingMarkerIsInsetRoundedAndPulsesUniformly() {
        count.intValue = 2
        show(working = true, progress = TerminalProgress("indeterminate", null))
        val leadingInset = with(ui.density) { 12.dp.roundToPx() }
        val centerOffset = with(ui.density) { 6.5.dp.roundToPx() }
        val topOffset = with(ui.density) { 8.dp.roundToPx() }
        fun sample(): androidx.compose.ui.graphics.Color {
            val pixels = tab(0).captureToImage().toPixelMap()
            val y = pixels.height - centerOffset
            val center = pixels[pixels.width / 2, y]
            assertEquals(center, pixels[pixels.width / 4, y])
            assertEquals(center, pixels[pixels.width * 3 / 4, y])
            assertNotEquals("There should be space beside the marker", center, pixels[0, y])
            assertNotEquals(
                "The corner should be rounded",
                center,
                pixels[leadingInset, pixels.height - topOffset],
            )
            return center
        }
        val first = sample()
        ui.waitUntil(2500) { sample() != first }
        tab(1).performClick().assertIsSelected()
        val inactive = tab(0).captureToImage().toPixelMap()
        val y = inactive.height - centerOffset
        assertEquals(
            "The previous tab should lose its marker even with ongoing progress",
            inactive[0, y],
            inactive[inactive.width / 2, y],
        )
    }

    @Test
    fun theBottomTabBarDrawsAnEdgeToEdgeTopDivider() {
        show()
        val pixels = ui.onNodeWithTag("Test dock").captureToImage().toPixelMap()
        val border = pixels[pixels.width / 2, 0]
        assertEquals(border, pixels[0, 0])
        assertEquals(border, pixels[pixels.width - 1, 0])
        assertNotEquals(border, pixels[0, 5])
    }

    private fun capture(name: String) {
        ui.mainClock.advanceTimeBy(400)
        ui.waitForIdle()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        File(app.cacheDir, "adaptive-$name.png").outputStream().use {
            ui.onNodeWithTag("Test dock")
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
