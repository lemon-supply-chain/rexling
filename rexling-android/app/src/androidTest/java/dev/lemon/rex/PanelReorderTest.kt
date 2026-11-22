package dev.lemon.rex

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PanelReorderTest {
    @get:Rule val ui=createComposeRule()
    private val windows=mutableStateOf(listOf(
        WindowItem("w0","One",listOf(BlockItem("a","Pane A"),BlockItem("b","Pane B"),BlockItem("c","Pane C")),
            listOf(PanePlacement("a",0f,0f,.4f,1f),PanePlacement("b",.4f,0f,.6f,.5f),PanePlacement("c",.4f,.5f,.6f,.5f))),
        WindowItem("w1","Two",listOf(BlockItem("x","X"))),WindowItem("w2","Three",listOf(BlockItem("y","Y")))))
    private val enabled=mutableStateOf(true)
    private val session=mutableStateOf("s")
    private val moves=mutableListOf<String>()
    private val actions=mutableListOf<String>()
    private fun show(page: Int=0) {
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {
            TabPaneSwitcher(windows.value,"w0","a",emptyMap(),emptyMap(),emptyMap(),{0},enabled.value,enabled.value,enabled.value,
                {actions+="tab:$it"},{actions+="pane:$it"},{actions+="new-tab"},{_,_->actions+="new-pane"},
                {actions+="close-tab"},{actions+="close-pane"},{actions+="dismiss"},page,
                onMoveTab={id,before->
                    moves+="tab:$id:$before"
                    val map=windows.value.associateBy {it.id}
                    windows.value=moveTabBefore(windows.value.map {it.id},id,before)!!.map {map.getValue(it)};true
                },onMovePane={window,id,before->
                    moves+="pane:$window:$id:$before"
                    windows.value=windows.value.map {w->if(w.id!=window) w else {
                        val map=w.blocks.associateBy {it.id}
                        w.copy(blocks=moveTabBefore(w.blocks.map {it.id},id,before)!!.map {map.getValue(it)})
                    }};true
                },reorderKey=session.value)
        }}
    }
    private fun row(label: String)=ui.onNodeWithContentDescription(label)
    private fun begin(list: SemanticsNodeInteraction,label: String) {
        val point=row(label).fetchSemanticsNode().boundsInRoot.center-list.fetchSemanticsNode().boundsInRoot.topLeft
        ui.mainClock.autoAdvance=false
        list.performTouchInput {down(point)}
        ui.mainClock.advanceTimeBy(700);ui.waitForIdle()
    }
    private fun finish(list: SemanticsNodeInteraction) {
        list.performTouchInput {up()};ui.mainClock.advanceTimeBy(500);ui.waitForIdle()
    }
    @Test fun holdingAndDraggingATabCommitsOnceWithoutSelectingOrOpeningIt() {
        show()
        val list=row("Reorder tabs")
        val end=row("Tab Three").fetchSemanticsNode().boundsInRoot.center-list.fetchSemanticsNode().boundsInRoot.topLeft
        begin(list,"Tab One")
        list.performTouchInput {moveTo(end+Offset(0f,10f),200)}
        ui.mainClock.advanceTimeBy(100);finish(list)
        ui.runOnIdle {assertEquals(listOf("w1","w2","w0"),windows.value.map {it.id});assertEquals(listOf("tab:w0:null"),moves);assertTrue(actions.isEmpty())}
        row("Tab One").assertIsSelected()
    }
    @Test fun draggingAPaneKeepsSelectionAndUsesItsWindowAndStableId() {
        show(page=1)
        val layout=windows.value.first().layout
        val list=row("Reorder panes")
        val end=row("Pane Pane C").fetchSemanticsNode().boundsInRoot.center-list.fetchSemanticsNode().boundsInRoot.topLeft
        begin(list,"Pane Pane A")
        list.performTouchInput {moveTo(end+Offset(0f,10f),200)}
        ui.mainClock.advanceTimeBy(100);finish(list)
        ui.runOnIdle {assertEquals(listOf("b","c","a"),windows.value.first().blocks.map {it.id});assertSame(layout,windows.value.first().layout);assertEquals(listOf("pane:w0:a:null"),moves);assertTrue(actions.isEmpty())}
        row("Pane Pane A").assertIsSelected()
    }
    @Test fun holdWithoutMovingAndCancelledDragSendNothing() {
        show(page=1);val list=row("Reorder panes")
        begin(list,"Pane Pane A");finish(list)
        begin(list,"Pane Pane A")
        list.performTouchInput {moveBy(Offset(0f,100f));cancel()};ui.mainClock.advanceTimeBy(500)
        ui.runOnIdle {assertTrue(moves.isEmpty());assertTrue(actions.isEmpty());assertEquals("a",windows.value.first().blocks.first().id)}
    }
    @Test fun metadataOrSessionChangeDuringADragCancelsTheCommit() {
        show(page=1);val list=row("Reorder panes")
        begin(list,"Pane Pane A")
        list.performTouchInput {moveBy(Offset(0f,90f))}
        ui.runOnIdle {session.value="other-session"}
        ui.mainClock.advanceTimeByFrame()
        finish(list)
        ui.runOnIdle {assertTrue(moves.isEmpty());assertTrue(actions.isEmpty())}
    }
    @Test fun mapLongPressAndDisabledListCannotReorder() {
        show(page=1)
        ui.onNodeWithText("Show map").performClick()
        val map=row("Pane layout")
        ui.mainClock.autoAdvance=false
        map.performTouchInput {down(center)};ui.mainClock.advanceTimeBy(700);ui.waitForIdle()
        map.performTouchInput {moveBy(Offset(0f,15f));up()};ui.mainClock.advanceTimeBy(300)
        ui.runOnIdle {enabled.value=false}
        val list=row("Reorder panes")
        begin(list,"Pane Pane A")
        list.performTouchInput {moveBy(Offset(0f,100f))};finish(list)
        ui.runOnIdle {assertTrue("Unexpected moves: $moves",moves.isEmpty());assertTrue("Unexpected navigation: $actions",actions.isEmpty())}
    }
    @Test fun edgeHoldScrollsToOffscreenPanes() {
        val blocks=(0..11).map {BlockItem("p$it","Pane $it")}
        windows.value=listOf(WindowItem("w0","One",blocks))
        show(page=1);val list=row("Reorder panes")
        begin(list,"Pane Pane 0")
        val bounds=list.fetchSemanticsNode().boundsInRoot
        list.performTouchInput {moveTo(Offset(bounds.width/2,bounds.height-2f),200)}
        ui.mainClock.advanceTimeBy(3000);ui.waitForIdle();finish(list)
        ui.runOnIdle {assertEquals("p0",windows.value.first().blocks.last().id);assertEquals(listOf("pane:w0:p0:null"),moves);assertTrue(actions.isEmpty())}
    }
    @Test fun accessibleMovesRespectBoundariesAndDoNotNavigate() {
        show(page=1)
        val first=row("Pane Pane A").fetchSemanticsNode().config[SemanticsActions.CustomActions].filter {it.label.startsWith("Move ")}
        ui.runOnIdle {assertEquals(listOf("Move down"),first.map {it.label});assertTrue(first.single().action())}
        ui.runOnIdle {assertEquals(listOf("b","a","c"),windows.value.first().blocks.map {it.id});assertTrue(actions.isEmpty())}
    }
}
