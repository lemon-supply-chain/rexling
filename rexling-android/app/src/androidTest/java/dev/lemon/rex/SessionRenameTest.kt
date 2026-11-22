package dev.lemon.rex
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionRenameTest {
    @get:Rule val ui=createComposeRule()
    private val target=SessionTarget(ServerProfile.create("Mac","https://example.test","mac"),SessionItem("s","old name"))
    @Test fun swipeToRenameDoesNotSelectOrCloseTheSession() {
        var selected=0;var closed=0;var renamed=0
        val revealed=mutableStateOf(false)
        ui.setContent {MaterialTheme {
            SessionRow(target,false,false,false,{selected++},{closed++},{renamed++},revealed.value,{revealed.value=it})
        }}
        ui.onNodeWithText("old name").swipeForSwitcherActions()
        ui.onNodeWithContentDescription("Rename session").performTouchInput {click()}
        ui.runOnIdle {assertEquals(1,renamed);assertEquals(0,selected);assertEquals(0,closed)}
    }
    @Test fun dialogPrefillsValidatesAndSubmitsATrimmedName() {
        var saved=""
        ui.setContent {MaterialTheme {SessionRenameDialog(target,true,{saved=it},{})}}
        ui.onNode(hasSetTextAction()).assertTextContains("old name")
        ui.onNodeWithText("Save").assertIsNotEnabled()
        ui.onNode(hasSetTextAction()).performTextReplacement("   ")
        ui.onNodeWithText("Save").assertIsNotEnabled()
        ui.onNode(hasSetTextAction()).performTextReplacement("🦀".repeat(129))
        ui.onNodeWithText("Use at most 128 characters").assertIsDisplayed()
        ui.onNodeWithText("Save").assertIsNotEnabled()
        ui.onNode(hasSetTextAction()).performTextReplacement("  Coding λ  ")
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {assertEquals("Coding λ",saved)}
    }
    @Test fun cancelDoesNotSendARename() {
        var saved=0;var cancelled=0
        ui.setContent {MaterialTheme {SessionRenameDialog(target,true,{saved++},{cancelled++})}}
        ui.onNode(hasSetTextAction()).performTextReplacement("changed")
        ui.onNodeWithText("Cancel").performClick()
        ui.runOnIdle {assertEquals(0,saved);assertEquals(1,cancelled)}
    }
}
