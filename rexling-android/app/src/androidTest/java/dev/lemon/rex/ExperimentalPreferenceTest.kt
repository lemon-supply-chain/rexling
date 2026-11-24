package dev.lemon.rex

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperimentalPreferenceTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun codexOptionIsClearlyExperimentalAndTogglesIndependently() {
        val enabled = mutableStateOf(false)
        var calls = 0
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                PreferencesSheet(
                    {},
                    {},
                    {},
                    {},
                    {},
                    codexTitleAttention = enabled.value,
                    onCodexTitleAttention = {
                        enabled.value = it
                        calls++
                    },
                )
            }
        }
        val toggle = ui.onNode(hasText("Codex title attention") and isToggleable())
        toggle.performScrollTo().assertIsOff().performClick().assertIsOn()
        ui.onNodeWithText(appString(R.string.section_experimental)).assertIsDisplayed()
        ui.onNodeWithText(
                "Use Codex’s explicit Action Required title when its status stays Working. Depends on Codex’s title format.",
            )
            .assertIsDisplayed()
        ui.runOnIdle { assertEquals(1, calls) }
        toggle.performClick().assertIsOff()
    }

    @Test
    fun keyboardSuggestionsOptionTogglesIndependently() {
        val enabled = mutableStateOf(false)
        var calls = 0
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                PreferencesSheet(
                    {},
                    {},
                    {},
                    {},
                    {},
                    keyboardSuggestionsEnabled = enabled.value,
                    onKeyboardSuggestions = {
                        enabled.value = it
                        calls++
                    },
                )
            }
        }
        val toggle = ui.onNode(hasText("Keyboard suggestions") and isToggleable())
        toggle.performScrollTo().assertIsOff().performClick().assertIsOn()
        ui.runOnIdle { assertEquals(1, calls) }
        toggle.performClick().assertIsOff()
        ui.runOnIdle { assertEquals(2, calls) }
    }

    @Test
    fun compactTopBarOptionTogglesIndependently() {
        val enabled = mutableStateOf(false)
        var calls = 0
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                PreferencesSheet(
                    {},
                    {},
                    {},
                    {},
                    {},
                    compactTopBar = enabled.value,
                    onCompactTopBar = {
                        enabled.value = it
                        calls++
                    },
                )
            }
        }
        val toggle = ui.onNode(hasText("Compact Top App Bar") and isToggleable())
        toggle.performScrollTo().assertIsOff().performClick().assertIsOn()
        ui.runOnIdle { assertEquals(1, calls) }
        toggle.performClick().assertIsOff()
        ui.runOnIdle { assertEquals(2, calls) }
    }
}
