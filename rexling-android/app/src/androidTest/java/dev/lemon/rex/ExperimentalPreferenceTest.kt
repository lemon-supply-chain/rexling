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
        val toggle =
            ui.onNode(hasText(appString(R.string.codex_title_attention)) and isToggleable())
        toggle.performScrollTo().assertIsOff().performClick().assertIsOn()
        ui.onNodeWithText(appString(R.string.section_experimental)).assertIsDisplayed()
        ui.onNodeWithText(
                appString(R.string.codex_title_attention_detail),
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
        val toggle = ui.onNode(hasText(appString(R.string.keyboard_suggestions)) and isToggleable())
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
        val toggle = ui.onNode(hasText(appString(R.string.compact_top_bar)) and isToggleable())
        toggle.performScrollTo().assertIsOff().performClick().assertIsOn()
        ui.runOnIdle { assertEquals(1, calls) }
        toggle.performClick().assertIsOff()
        ui.runOnIdle { assertEquals(2, calls) }
    }
}
