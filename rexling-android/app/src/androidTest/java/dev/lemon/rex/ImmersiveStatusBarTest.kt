package dev.lemon.rex

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImmersiveStatusBarTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test
    fun statusBarOptionDefaultsOffAndPersistsAcrossStarts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("rex", 0)
        val key = "immersive_status_bar"
        val saved = prefs.getBoolean(key, false).takeIf { prefs.contains(key) }
        prefs.edit().remove(key).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForStatusBar(scenario, visible = true)
                val headerIcon =
                    ui.onNodeWithContentDescription(appString(R.string.a11y_session_program_icon))
                val initialTop = headerIcon.fetchSemanticsNode().boundsInWindow.top
                val option = showPreferences()
                option.assertIsOff().performClick().assertIsOn()
                ui.onNodeWithContentDescription(appString(R.string.close_preferences))
                    .performClick()
                waitForStatusBar(scenario, visible = false)
                ui.waitUntil(5000) {
                    headerIcon.fetchSemanticsNode().boundsInWindow.top < initialTop
                }
                assertTrue(
                    "The workspace should reclaim at least half a header row",
                    initialTop - headerIcon.fetchSemanticsNode().boundsInWindow.top >=
                        with(ui.density) { 16.dp.toPx() },
                )
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForStatusBar(scenario, visible = false)
                showPreferences().assertIsOn().performClick().assertIsOff()
                ui.onNodeWithContentDescription(appString(R.string.close_preferences))
                    .performClick()
                waitForStatusBar(scenario, visible = true)
            }
        } finally {
            prefs
                .edit()
                .apply { if (saved == null) remove(key) else putBoolean(key, saved) }
                .commit()
        }
    }

    private fun showPreferences(): SemanticsNodeInteraction {
        ui.onNodeWithContentDescription(appString(R.string.terminal_menu)).performClick()
        ui.onNodeWithContentDescription(appString(R.string.preferences)).performClick()
        return ui.onNodeWithText(appString(R.string.immersive_mode)).performScrollTo()
    }

    private fun waitForStatusBar(scenario: ActivityScenario<MainActivity>, visible: Boolean) {
        ui.waitUntil(8000) {
            var matches = false
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                val insets = ViewCompat.getRootWindowInsets(view)
                val behavior =
                    WindowCompat.getInsetsController(activity.window, view).systemBarsBehavior
                val expected =
                    if (visible) WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                    else WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                matches =
                    insets != null &&
                        insets.isVisible(WindowInsetsCompat.Type.statusBars()) == visible &&
                        insets.isVisible(WindowInsetsCompat.Type.navigationBars()) &&
                        behavior == expected
            }
            matches
        }
    }
}
