package dev.lemon.rex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabReorderTest {
    @get:Rule val ui=createComposeRule()
    private val order=mutableStateOf(listOf("One","Two","Three","Four","Five","Six"))
    private val enabled=mutableStateOf(true)
    private val session=mutableStateOf("session-one")
    private val moves=mutableListOf<Pair<String,String?>>()
    private val selections=mutableListOf<String>()
    private val closed=mutableListOf<String>()
    private fun show() {
        ui.setContent {MaterialTheme {Box(Modifier.width(300.dp)) {
            TerminalTabs(order.value.map {WindowItem(it,it,listOf(BlockItem(it,"fish")))},"One",enabled.value,
                onSelect={selections.add(it)},reorderKey=session.value,panePrograms={listOf(ProgramIdentity.Terminal)},selectedPane={0},
                onMove={id,before->moves.add(id to before);order.value=moveTabBefore(order.value,id,before)!!;true},onClose={closed.add(it.id)},actions={})
        }}}
    }
    private fun tab(id: String)=ui.onNodeWithContentDescription("Tab $id, Terminal")
    @Test fun holdingAndReleasingKeepsCloseAndMoveOptions() {
        show();ui.mainClock.autoAdvance=false
        tab("One").performTouchInput {down(center)}
        ui.mainClock.advanceTimeBy(700);ui.waitForIdle()
        tab("One").performTouchInput {up()}
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithText("Close tab").assertExists()
        ui.onNodeWithText("Move left").assertIsNotEnabled()
        ui.onNodeWithText("Move right").performClick()
        ui.runOnIdle {assertEquals(listOf("Two","One","Three","Four","Five","Six"),order.value);assertEquals(listOf("One" to "Three"),moves);assertTrue(selections.isEmpty())}
        tab("One").assertIsSelected()
        tab("One").performSemanticsAction(SemanticsActions.OnLongClick) {it()}
        ui.onNodeWithText("Close tab").performClick();ui.runOnIdle {assertEquals(listOf("One"),closed)}
    }
    @Test fun dragChangesOrderOnceWithoutChangingSelection() {
        show()
        val start=tab("One").fetchSemanticsNode().boundsInRoot.center
        val end=tab("Three").fetchSemanticsNode().boundsInRoot.center
        val root=ui.onNodeWithContentDescription("Tabs").fetchSemanticsNode().boundsInRoot.topLeft
        ui.mainClock.autoAdvance=false
        ui.onNodeWithContentDescription("Tabs").performTouchInput {down(start-root)}
        ui.mainClock.advanceTimeBy(700);ui.waitForIdle()
        ui.onNodeWithContentDescription("Tabs").performTouchInput {moveTo(end-root+Offset(10f,0f),200)}
        ui.mainClock.advanceTimeBy(100);ui.waitForIdle()
        ui.onNodeWithContentDescription("Tabs").performTouchInput {up()}
        ui.mainClock.advanceTimeBy(500)
        ui.runOnIdle {assertEquals(listOf("Two","Three","One","Four","Five","Six"),order.value);assertEquals(1,moves.size);assertTrue(selections.isEmpty())}
        tab("One").assertIsSelected();ui.onNodeWithText("Close tab").assertDoesNotExist()
    }
    @Test fun normalSwipeScrollsAndCancelledDragDoesNotMutateOrder() {
        show();ui.onNodeWithContentDescription("Tabs").performTouchInput {swipeLeft()}
        ui.runOnIdle {assertTrue(moves.isEmpty())}
        ui.onNodeWithContentDescription("Tabs").performTouchInput {swipeRight()}
        ui.waitForIdle()
        ui.mainClock.autoAdvance=false
        tab("One").performTouchInput {down(center)}
        ui.mainClock.advanceTimeBy(700);ui.waitForIdle()
        ui.onNodeWithContentDescription("Tabs").performTouchInput {moveBy(Offset(90f,0f))}
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Tabs").performTouchInput {cancel()}
        ui.mainClock.advanceTimeBy(500)
        ui.runOnIdle {assertTrue(moves.isEmpty());assertEquals("One",order.value.first())}
        ui.onNodeWithText("Close tab").assertDoesNotExist()
    }
    @Test fun edgeHoldScrollsToTabsThatStartedOffscreen() {
        show();ui.mainClock.autoAdvance=false
        val strip=ui.onNodeWithContentDescription("Tabs")
        val start=tab("One").fetchSemanticsNode().boundsInRoot.center
        val bounds=strip.fetchSemanticsNode().boundsInRoot
        strip.performTouchInput {down(start-bounds.topLeft)}
        ui.mainClock.advanceTimeBy(700);ui.waitForIdle()
        strip.performTouchInput {moveTo(Offset(bounds.width-2f,bounds.height/2f),200)}
        ui.mainClock.advanceTimeBy(2000);ui.waitForIdle()
        strip.performTouchInput {up()};ui.mainClock.advanceTimeBy(500)
        ui.runOnIdle {assertEquals("One",order.value.last());assertEquals(listOf("One" to null),moves);assertTrue(selections.isEmpty())}
    }
    @Test fun changingSessionDuringADragCancelsIt() {
        show();ui.mainClock.autoAdvance=false
        tab("One").performTouchInput {down(center)}
        ui.mainClock.advanceTimeBy(700);ui.waitForIdle()
        ui.runOnIdle {session.value="session-two"}
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Tabs").performTouchInput {moveBy(Offset(140f,0f));up()}
        ui.mainClock.advanceTimeByFrame()
        ui.runOnIdle {assertTrue(moves.isEmpty())}
        ui.onNodeWithText("Close tab").assertDoesNotExist()
    }
    @Test fun accessibleMovesAndDisabledStripRespectBoundaries() {
        show();val actions=tab("One").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        ui.runOnIdle {assertEquals(listOf("Move right"),actions.map {it.label});assertTrue(actions.single().action())}
        ui.runOnIdle {enabled.value=false}
        tab("One").performTouchInput {down(center);advanceEventTime(700);moveBy(Offset(100f,0f));up()}
        ui.runOnIdle {assertEquals(1,moves.size)}
    }
}
