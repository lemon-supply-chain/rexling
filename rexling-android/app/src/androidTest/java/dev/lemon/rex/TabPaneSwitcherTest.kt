package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabPaneSwitcherTest {
    @get:Rule val ui = createComposeRule()
    private val windows =
        mutableStateOf(
            listOf(
                WindowItem(
                    "w1",
                    "Project",
                    listOf(
                        BlockItem("a", "Terminal"),
                        BlockItem("b", "Claude"),
                        BlockItem("d", "nvim"),
                    ),
                    listOf(
                        PanePlacement("a", 0f, 0f, .4f, 1f),
                        PanePlacement("b", .4f, 0f, .6f, .5f),
                        PanePlacement("d", .4f, .5f, .6f, .5f),
                    ),
                ),
                WindowItem("w2", "Terminal 2", listOf(BlockItem("c", "Codex"))),
            ),
        )
    private val programs =
        mapOf(
            "a" to ProgramIdentity.resolve(listOf("fish")),
            "b" to ProgramIdentity.resolve(listOf("claude")),
            "c" to ProgramIdentity.resolve(listOf("codex")),
            "d" to ProgramIdentity.resolve(listOf("nvim")),
        )
    private val calls = mutableListOf<String>()

    private fun show(
        enabled: Boolean = true,
        initialPage: Int = 0,
        empty: Boolean = false,
        dark: Boolean = false,
        selectedBlock: String = "b",
        withoutLayout: Boolean = false,
    ) {
        ui.setContent {
            RexTheme(dark, ThemeCatalog.defaults[if (dark) 0 else 1]) {
                TabPaneSwitcher(
                    if (empty) emptyList()
                    else if (withoutLayout) windows.value.map { it.copy(layout = emptyList()) }
                    else windows.value,
                    "w1",
                    selectedBlock,
                    programs,
                    mapOf("b" to "Plan next steps", "c" to "Review changes"),
                    mapOf("a" to "~/Dev/rexling", "b" to "~/Dev/portside"),
                    { if (it.id == "w1") 1 else 0 },
                    enabled,
                    enabled,
                    enabled && !empty,
                    { calls += "tab:$it" },
                    { calls += "pane:$it" },
                    { calls += "new-tab" },
                    { anchor, split -> calls += "new-pane:$anchor:${split.direction}" },
                    { calls += "close-tab:${it.id}" },
                    { calls += "close-pane:${it.id}" },
                    { calls += "dismiss" },
                    initialPage,
                )
            }
        }
    }

    private fun row(description: String) = ui.onNodeWithContentDescription(description)

    private fun action(label: String) = ui.onNodeWithContentDescription(label)

    @Test
    fun tappingATabOrPaneOpensItDirectly() {
        show()
        row(appString(R.string.a11y_tab_title, "Project")).assertIsSelected()
        ui.onNodeWithText("Project", useUnmergedTree = true).assertPaneTitleTypography()
        ui.onNodeWithText(
                "${appString(R.string.viewing)} · ${appQuantity(R.plurals.pane_count, 3)} · ~/Dev/portside",
                useUnmergedTree = true,
            )
            .assertPaneDetailTypography()
        ui.onNodeWithText("Open tab").assertDoesNotExist()
        capture("tabs-light")
        row(appString(R.string.a11y_tab_title, "Review changes")).performTouchInput { click() }
        ui.onNodeWithText(appString(R.string.section_panes)).performClick()
        row(appString(R.string.a11y_pane_title, "Plan next steps")).assertIsSelected()
        ui.onNodeWithText("Plan next steps", useUnmergedTree = true).assertPaneTitleTypography()
        ui.onNodeWithText("${appString(R.string.viewing)} · ~/Dev/portside", useUnmergedTree = true)
            .assertPaneDetailTypography()
        row(appString(R.string.a11y_pane_title, "fish")).performTouchInput { click() }
        ui.onNodeWithText("Open pane").assertDoesNotExist()
        ui.runOnIdle { assertEquals(listOf("tab:w2", "pane:a"), calls) }
        ui.onNodeWithContentDescription(appString(R.string.a11y_close_tab_and_pane_switcher))
            .performClick()
        ui.waitUntil(5_000) { ui.runOnUiThread { "dismiss" in calls } }
        ui.runOnIdle { assertEquals(listOf("tab:w2", "pane:a", "dismiss"), calls) }
    }

    @Test
    fun horizontalSwipesRevealOneRowAtATimeAndKeepTheSection() {
        show(dark = true)
        row(appString(R.string.a11y_tab_title, "Project")).swipeForSwitcherActions()
        action(appString(R.string.label_rename_tab)).assertIsDisplayed()
        if (
            ui.onAllNodesWithContentDescription(
                    appString(R.string.a11y_tab_title, "Review changes"),
                )
                .fetchSemanticsNodes()
                .isEmpty()
        ) {
            row(appString(R.string.reorder_tabs)).performScrollToKey("w2")
        }
        row(appString(R.string.a11y_tab_title, "Review changes"))
            .performScrollTo()
            .swipeForSwitcherActions()
        action(appString(R.string.label_rename_tab)).assertIsDisplayed()
        val rowBounds =
            row(appString(R.string.a11y_tab_title, "Review changes"))
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            action(appString(R.string.label_rename_tab))
                .fetchSemanticsNode()
                .boundsInRoot
                .center
                .y in rowBounds.top..rowBounds.bottom,
        )
        row(appString(R.string.a11y_tab_title, "Review changes")).performTouchInput { swipeRight() }
        action(appString(R.string.label_rename_tab)).assertDoesNotExist()
        ui.onNodeWithText(appString(R.string.label_new_tab)).assertIsDisplayed()
        ui.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test
    fun closeAndSplitActionsUseTheSwipedRow() {
        show()
        ui.onNodeWithText(appString(R.string.label_new_tab)).performClick()
        row(appString(R.string.a11y_tab_title, "Project")).swipeForSwitcherActions()
        action(appString(R.string.close_tab)).performTouchInput { click() }
        ui.onNodeWithText(appString(R.string.section_panes)).performClick()
        row(appString(R.string.a11y_pane_title, "Plan next steps")).swipeForSwitcherActions()
        action(appString(R.string.split_right)).performTouchInput { click() }
        row(appString(R.string.a11y_pane_title, "nvim")).swipeForSwitcherActions()
        capture("pane-actions")
        action(appString(R.string.split_below)).performTouchInput { click() }
        row(appString(R.string.a11y_pane_title, "fish")).swipeForSwitcherActions()
        action(appString(R.string.close_pane)).performTouchInput { click() }
        ui.runOnIdle {
            assertEquals(
                listOf(
                    "new-tab",
                    "close-tab:w1",
                    "new-pane:b:horizontal",
                    "new-pane:d:vertical",
                    "close-pane:a",
                ),
                calls,
            )
        }
    }

    @Test
    fun tappingARevealedPaneDismissesItsActionsBeforeItCanOpen() {
        show(initialPage = 1)
        val pane = row(appString(R.string.a11y_pane_title, "nvim"))
        pane.swipeForSwitcherActions()
        action(appString(R.string.label_rename_pane)).assertIsDisplayed()
        ui.onNodeWithTag("Pane position: d", useUnmergedTree = true).performTouchInput { click() }
        action(appString(R.string.label_rename_pane)).assertDoesNotExist()
        ui.runOnIdle { assertTrue(calls.isEmpty()) }
        pane.performTouchInput { click() }
        ui.runOnIdle { assertEquals(listOf("pane:d"), calls) }
    }

    @Test
    fun busyStateDisablesTerminalActionsButAllowsBrowsingSections() {
        show(enabled = false)
        row(appString(R.string.a11y_tab_title, "Project"))
            .assertIsNotEnabled()
            .swipeForSwitcherActions()
        action(appString(R.string.close_tab)).assertDoesNotExist()
        ui.onNodeWithText(appString(R.string.label_new_tab)).assertIsNotEnabled()
        ui.onNodeWithText(appString(R.string.section_panes)).performClick()
        row(appString(R.string.a11y_pane_title, "fish"))
            .assertIsNotEnabled()
            .swipeForSwitcherActions()
        action(appString(R.string.split_right)).assertDoesNotExist()
        ui.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test
    fun emptySessionOffersANewTabButCannotAddAnUnanchoredPane() {
        show(empty = true)
        ui.onNodeWithText(appString(R.string.no_tabs_yet)).assertIsDisplayed()
        ui.onNodeWithText(appString(R.string.label_new_tab)).performClick()
        ui.onNodeWithText(appString(R.string.section_panes)).performClick()
        ui.onNodeWithText(appString(R.string.no_panes_yet)).assertIsDisplayed()
        action(appString(R.string.split_below)).assertDoesNotExist()
        ui.runOnIdle { assertEquals(listOf("new-tab"), calls) }
    }

    @Test
    fun paneIndicatorCanOpenDirectlyAndTheLayoutOpensItsPane() {
        show(initialPage = 1, dark = true)
        row(appString(R.string.a11y_pane_title, "Plan next steps")).assertIsSelected()
        row(appString(R.string.a11y_pane_layout)).assertDoesNotExist()
        for (id in listOf("a", "b", "d")) ui.onNodeWithTag(
                "Pane position: $id",
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        capture("panes-dark")
        ui.onNodeWithText(appString(R.string.show_map)).performClick()
        row(appString(R.string.a11y_pane_layout)).assertIsDisplayed()
        row(appString(R.string.a11y_layout_pane_format, "nvim")).performClick()
        ui.onNodeWithText(appString(R.string.hide_map)).performClick()
        row(appString(R.string.a11y_pane_layout)).assertDoesNotExist()
        ui.runOnIdle { assertEquals(listOf("pane:d"), calls) }
    }

    @Test
    fun unavailableGeometryStillAllowsSplittingTheSwipedPane() {
        show(initialPage = 1, withoutLayout = true, selectedBlock = "stale-pane")
        ui.onNodeWithText(appString(R.string.show_map)).assertDoesNotExist()
        row(appString(R.string.a11y_pane_layout)).assertDoesNotExist()
        ui.onNodeWithTag("Pane position: a", useUnmergedTree = true).assertDoesNotExist()
        row(appString(R.string.a11y_pane_title, "fish")).swipeForSwitcherActions()
        action(appString(R.string.split_below)).performClick()
        ui.runOnIdle { assertEquals(listOf("new-pane:a:vertical"), calls) }
    }

    @Test
    fun removingTheRevealedPaneHidesItsActions() {
        show(initialPage = 1, selectedBlock = "a")
        row(appString(R.string.a11y_pane_title, "nvim")).swipeForSwitcherActions()
        action(appString(R.string.split_right)).assertIsDisplayed()
        ui.runOnIdle {
            windows.value =
                windows.value.map {
                    it.copy(
                        blocks = it.blocks.filterNot { b -> b.id == "d" },
                        layout = it.layout.filterNot { p -> p.id == "d" },
                    )
                }
        }
        action(appString(R.string.split_right)).assertDoesNotExist()
        ui.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test
    fun compactDrawerShowsFivePanesWithTheirPositions() {
        val blocks =
            windows.value.first().blocks + listOf(BlockItem("e", "Build"), BlockItem("f", "Logs"))
        windows.value =
            windows.value.mapIndexed { index, w ->
                if (index == 0)
                    w.copy(
                        blocks = blocks,
                        layout =
                            blocks.mapIndexed { i, b -> PanePlacement(b.id, i * .2f, 0f, .2f, 1f) },
                    )
                else w
            }
        show(initialPage = 1)
        row(appString(R.string.a11y_pane_layout)).assertDoesNotExist()
        for (title in listOf("fish", "Plan next steps", "nvim", "Build", "Logs")) row(
                appString(R.string.a11y_pane_title, title),
            )
            .assertIsDisplayed()
        capture("five-panes")
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        File(instrumentation.targetContext.cacheDir, "switcher-$name.png").outputStream().use {
            instrumentation.uiAutomation
                .takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

internal fun SemanticsNodeInteraction.assertPaneTitleTypography() =
    assertDrawerTypography(13.sp, 16.sp, FontWeight.SemiBold)

internal fun SemanticsNodeInteraction.assertPaneDetailTypography() =
    assertDrawerTypography(11.sp, 14.sp, FontWeight.Normal)

private fun SemanticsNodeInteraction.assertDrawerTypography(
    size: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight,
) {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get -> assertTrue(get(layouts)) }
    val style = layouts.single().layoutInput.style
    assertEquals(size, style.fontSize)
    assertEquals(lineHeight, style.lineHeight)
    assertEquals(weight, style.fontWeight)
}

internal fun SemanticsNodeInteraction.swipeForSwitcherActions() = performTouchInput { swipeLeft() }
