package dev.lemon.rex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageSheetTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun languageSheetRendersOptionsAndInvokesCallbackOnSelection() {
        val selected = mutableStateOf("system")
        var closeCalls = 0
        ui.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    LanguageSheet(
                        language = selected.value,
                        onLanguage = { selected.value = it },
                        onClose = { closeCalls++ },
                    )
                }
            }
        }

        ui.onNodeWithText("Language").assertIsDisplayed()
        ui.onNodeWithText("System default").assertIsDisplayed()
        ui.onNodeWithText("English").assertIsDisplayed()
        ui.onNodeWithText("简体中文").assertIsDisplayed()

        ui.onNodeWithText("English").performClick()
        ui.runOnIdle { assertEquals("en", selected.value) }

        ui.onNodeWithText("简体中文").performClick()
        ui.runOnIdle { assertEquals("zh", selected.value) }

        ui.onNodeWithContentDescription("Close language settings").performClick()
        ui.runOnIdle { assertEquals(1, closeCalls) }
    }
}
