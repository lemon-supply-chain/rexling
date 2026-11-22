package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TabPaneSwitcherTest {
    @get:Rule val ui=createComposeRule()
    private val windows=mutableStateOf(listOf(WindowItem("w1","Project",listOf(BlockItem("a","Terminal"),BlockItem("b","Claude"),BlockItem("d","nvim")),
        listOf(PanePlacement("a",0f,0f,.4f,1f),PanePlacement("b",.4f,0f,.6f,.5f),PanePlacement("d",.4f,.5f,.6f,.5f))),
        WindowItem("w2","Terminal 2",listOf(BlockItem("c","Codex")))))
    private val programs=mapOf("a" to ProgramIdentity.resolve(listOf("fish")),"b" to ProgramIdentity.resolve(listOf("claude")),"c" to ProgramIdentity.resolve(listOf("codex")),"d" to ProgramIdentity.resolve(listOf("nvim")))
    private val calls=mutableListOf<String>()
    private fun show(enabled: Boolean=true,initialPage: Int=0,empty: Boolean=false,dark: Boolean=false,selectedBlock: String="b",withoutLayout: Boolean=false) {
        ui.setContent {RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
            TabPaneSwitcher(if(empty) emptyList() else if(withoutLayout) windows.value.map {it.copy(layout=emptyList())} else windows.value,"w1",selectedBlock,programs,
                mapOf("b" to "Plan next steps","c" to "Review changes"),mapOf("a" to "~/Dev/rexling","b" to "~/Dev/portside"),
                {if(it.id=="w1") 1 else 0},enabled,enabled,enabled && !empty,
                {calls+="tab:$it"},{calls+="pane:$it"},{calls+="new-tab"},{anchor,split->calls+="new-pane:$anchor:${split.direction}"},
                {calls+="close-tab:${it.id}"},{calls+="close-pane:${it.id}"},{calls+="dismiss"},initialPage)
        }}
    }
    private fun row(description: String)=ui.onNodeWithContentDescription(description)
    private fun action(label: String)=ui.onNodeWithContentDescription(label)

    @Test fun tappingATabOrPaneOpensItDirectly() {
        show()
        row("Tab Project").assertIsSelected()
        ui.onNodeWithText("Open tab").assertDoesNotExist()
        capture("tabs-light")
        row("Tab Review changes").performTouchInput {click()}
        ui.onNodeWithText("Panes").performClick()
        row("Pane Plan next steps").assertIsSelected()
        row("Pane fish").performTouchInput {click()}
        ui.onNodeWithText("Open pane").assertDoesNotExist()
        ui.runOnIdle {assertEquals(listOf("tab:w2","pane:a"),calls)}
    }

    @Test fun horizontalSwipesRevealOneRowAtATimeAndKeepTheSection() {
        show(dark=true)
        row("Tab Project").swipeForSwitcherActions()
        action("Rename tab").assertIsDisplayed()
        if(ui.onAllNodesWithContentDescription("Tab Review changes").fetchSemanticsNodes().isEmpty()) {
            row("Reorder tabs").performScrollToKey("w2")
        }
        row("Tab Review changes").performScrollTo().swipeForSwitcherActions()
        action("Rename tab").assertIsDisplayed()
        val rowBounds=row("Tab Review changes").fetchSemanticsNode().boundsInRoot
        assertTrue(action("Rename tab").fetchSemanticsNode().boundsInRoot.center.y in rowBounds.top..rowBounds.bottom)
        row("Tab Review changes").performTouchInput {swipeRight()}
        action("Rename tab").assertDoesNotExist()
        ui.onNodeWithText("New tab").assertIsDisplayed()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
    }

    @Test fun closeAndSplitActionsUseTheSwipedRow() {
        show()
        ui.onNodeWithText("New tab").performClick()
        row("Tab Project").swipeForSwitcherActions()
        action("Close tab").performTouchInput {click()}
        ui.onNodeWithText("Panes").performClick()
        row("Pane Plan next steps").swipeForSwitcherActions()
        action("Split right").performTouchInput {click()}
        row("Pane nvim").swipeForSwitcherActions()
        capture("pane-actions")
        action("Split below").performTouchInput {click()}
        row("Pane fish").swipeForSwitcherActions()
        action("Close pane").performTouchInput {click()}
        ui.runOnIdle {assertEquals(listOf("new-tab","close-tab:w1","new-pane:b:horizontal","new-pane:d:vertical","close-pane:a"),calls)}
    }

    @Test fun tappingARevealedPaneDismissesItsActionsBeforeItCanOpen() {
        show(initialPage=1)
        val pane=row("Pane nvim")
        pane.swipeForSwitcherActions()
        action("Rename pane").assertIsDisplayed()
        ui.onNodeWithTag("Pane position: d",useUnmergedTree=true).performTouchInput {click()}
        action("Rename pane").assertDoesNotExist()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
        pane.performTouchInput {click()}
        ui.runOnIdle {assertEquals(listOf("pane:d"),calls)}
    }

    @Test fun busyStateDisablesTerminalActionsButAllowsBrowsingSections() {
        show(enabled=false)
        row("Tab Project").assertIsNotEnabled().swipeForSwitcherActions()
        action("Close tab").assertDoesNotExist()
        ui.onNodeWithText("New tab").assertIsNotEnabled()
        ui.onNodeWithText("Panes").performClick()
        row("Pane fish").assertIsNotEnabled().swipeForSwitcherActions()
        action("Split right").assertDoesNotExist()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
    }

    @Test fun emptySessionOffersANewTabButCannotAddAnUnanchoredPane() {
        show(empty=true)
        ui.onNodeWithText("No tabs yet").assertIsDisplayed()
        ui.onNodeWithText("New tab").performClick()
        ui.onNodeWithText("Panes").performClick()
        ui.onNodeWithText("No panes yet").assertIsDisplayed()
        action("Split below").assertDoesNotExist()
        ui.runOnIdle {assertEquals(listOf("new-tab"),calls)}
    }

    @Test fun paneIndicatorCanOpenDirectlyAndTheLayoutOpensItsPane() {
        show(initialPage=1,dark=true)
        row("Pane Plan next steps").assertIsSelected()
        row("Pane layout").assertDoesNotExist()
        for(id in listOf("a","b","d")) ui.onNodeWithTag("Pane position: $id",useUnmergedTree=true).assertIsDisplayed()
        capture("panes-dark")
        ui.onNodeWithText("Show map").performClick()
        row("Pane layout").assertIsDisplayed()
        row("Layout pane nvim").performClick()
        ui.onNodeWithText("Hide map").performClick()
        row("Pane layout").assertDoesNotExist()
        ui.runOnIdle {assertEquals(listOf("pane:d"),calls)}
    }

    @Test fun unavailableGeometryStillAllowsSplittingTheSwipedPane() {
        show(initialPage=1,withoutLayout=true,selectedBlock="stale-pane")
        ui.onNodeWithText("Show map").assertDoesNotExist()
        row("Pane layout").assertDoesNotExist()
        ui.onNodeWithTag("Pane position: a",useUnmergedTree=true).assertDoesNotExist()
        row("Pane fish").swipeForSwitcherActions()
        action("Split below").performClick()
        ui.runOnIdle {assertEquals(listOf("new-pane:a:vertical"),calls)}
    }

    @Test fun removingTheRevealedPaneHidesItsActions() {
        show(initialPage=1,selectedBlock="a")
        row("Pane nvim").swipeForSwitcherActions()
        action("Split right").assertIsDisplayed()
        ui.runOnIdle {windows.value=windows.value.map {it.copy(blocks=it.blocks.filterNot {b->b.id=="d"},layout=it.layout.filterNot {p->p.id=="d"})}}
        action("Split right").assertDoesNotExist()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
    }

    @Test fun compactDrawerShowsFivePanesWithTheirPositions() {
        val blocks=windows.value.first().blocks+listOf(BlockItem("e","Build"),BlockItem("f","Logs"))
        windows.value=windows.value.mapIndexed {index,w->if(index==0) w.copy(blocks=blocks,layout=blocks.mapIndexed {i,b->PanePlacement(b.id,i*.2f,0f,.2f,1f)}) else w}
        show(initialPage=1)
        row("Pane layout").assertDoesNotExist()
        for(title in listOf("fish","Plan next steps","nvim","Build","Logs")) row("Pane $title").assertIsDisplayed()
        capture("five-panes")
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync();Thread.sleep(300)
        File(instrumentation.targetContext.cacheDir,"switcher-$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}

internal fun SemanticsNodeInteraction.swipeForSwitcherActions()=performTouchInput {swipeLeft()}
