package dev.lemon.rex

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CustomControlShortcutsTest {
    @get:Rule val ui=createComposeRule()
    @Test fun customSequenceSendsOnceAndChangingPanesClosesThePopup() {
        val target=mutableStateOf("pane-a");val sent=mutableListOf<String>()
        val items=ControlShortcutConfig.parse("Workspace = Space + L\nL")
        ui.setContent {MaterialTheme {Box(Modifier.size(340.dp,360.dp)) {
            ControlDockKey(true,false,true,Modifier.align(Alignment.BottomCenter).size(36.dp),{}, {sent+=ControlShortcutConfig.decode(it)},shortcuts=items,inputKey=target.value)
        }}}
        open();ui.onNodeWithContentDescription("Control shortcut Workspace").performClick()
        ui.runOnIdle {assertEquals(listOf("\u0000l"),sent)}
        open();ui.runOnIdle {target.value="pane-b"}
        ui.onNodeWithContentDescription("Control shortcuts").assertDoesNotExist()
        ui.runOnIdle {assertEquals(1,sent.size)}
    }
    @Test fun moreThanTwelveShortcutsHavePagesAndUseTheRightEntry() {
        val sent=mutableListOf<String>()
        val items=ControlShortcutConfig.parse((0..13).joinToString("\n") {"Key $it = ${('a'.code+it).toChar()}"})
        ui.setContent {MaterialTheme {Box(Modifier.size(340.dp,400.dp)) {
            ControlDockKey(true,false,true,Modifier.align(Alignment.BottomCenter).size(36.dp),{}, {sent+=it},shortcuts=items)
        }}}
        open();ui.onNodeWithContentDescription("Next Ctrl shortcuts page").performClick()
        ui.onNodeWithText("2/2").assertIsDisplayed()
        ui.onNodeWithContentDescription("Control shortcut Key 13").performClick()
        ui.runOnIdle {assertEquals(listOf("n"),sent)}
    }
    @Test fun recorderAddsNamedSpacePrefixAndSavesTheList() {
        var saved: List<ControlShortcut>?=null;var closed=false
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {ControlShortcutsSheet(ControlShortcutConfig.defaults,{text->
            runCatching {saved=ControlShortcutConfig.parse(text)}.exceptionOrNull()?.message
        },{closed=true})}}
        capture("list")
        ui.onNodeWithText("Add shortcut").performClick()
        ui.onNodeWithText("Space",substring=false).performClick()
        ui.onNodeWithText("Press keys").performTextInput("l")
        ui.onNodeWithText("Ctrl+Space").assertExists()
        capture("recorder")
        ui.onNodeWithText("Name (optional)").performTextInput("Workspace")
        ui.onNodeWithText("Done").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Ctrl shortcut list").performScrollToNode(hasContentDescription("Edit Ctrl shortcut Workspace"))
        ui.onNodeWithContentDescription("Edit Ctrl shortcut Workspace").assertIsDisplayed()
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {assertTrue(closed);assertEquals("\u0000l",ControlShortcutConfig.decode(saved!!.last().keys))}
    }
    @Test fun recorderUndoClearCancelAndDragReorderKeepTheRightEntries() {
        var saved: List<ControlShortcut>?=null
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {ControlShortcutsSheet(ControlShortcutConfig.defaults.take(3),{text->
            runCatching {saved=ControlShortcutConfig.parse(text)}.exceptionOrNull()?.message
        },{})}}
        ui.onNodeWithContentDescription("Edit Ctrl shortcut ^C").performClick()
        ui.onNodeWithText("Clear").performClick()
        ui.onNodeWithText("Press keys").performTextInput("ab")
        ui.onNodeWithText("Ctrl+A").assertExists();ui.onNodeWithText("B",substring=false).assertExists()
        ui.onNodeWithText("Undo").performClick()
        ui.onNodeWithText("B",substring=false).assertDoesNotExist()
        ui.onNodeWithText("Press keys").performImeAction()
        ui.onAllNodesWithText("Enter",substring=false).assertCountEquals(2)
        ui.onNodeWithText("Cancel",substring=false).performScrollTo().performClick()
        val from=ui.onNodeWithContentDescription("Edit Ctrl shortcut ^C").fetchSemanticsNode().boundsInRoot
        val to=ui.onNodeWithContentDescription("Edit Ctrl shortcut ^Z").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithContentDescription("Edit Ctrl shortcut ^C").performTouchInput {down(center);advanceEventTime(700);moveTo(to.center-from.topLeft,delayMillis=300);up()}
        ui.onNodeWithContentDescription("Remove Ctrl shortcut ^Z").performClick()
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {assertEquals(listOf("d","c"),saved!!.map {it.keys})}
    }
    private fun capture(name: String) {
        ui.waitForIdle();Thread.sleep(250)
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.cacheDir,"ctrl-shortcuts-$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }
    private fun open() {ui.onNodeWithContentDescription("Control").performSemanticsAction(SemanticsActions.OnLongClick) {it()}}
}
