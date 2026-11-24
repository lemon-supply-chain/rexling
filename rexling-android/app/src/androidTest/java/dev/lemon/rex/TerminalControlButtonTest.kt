package dev.lemon.rex

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalControlButtonTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun ownershipRemainsAvailableAsAMenuAction() {
        ui.setContent {
            var controlled by remember { mutableStateOf(false) }
            MaterialTheme { OwnershipMenuItem(controlled, true) { controlled = !controlled } }
        }
        ui.onNodeWithText("Take control").performClick()
        ui.onNodeWithText(appString(R.string.release_control)).assertIsDisplayed().performClick()
        ui.onNodeWithText("Take control").assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.a11y_take_terminal_control))
            .assertDoesNotExist()
    }
}
