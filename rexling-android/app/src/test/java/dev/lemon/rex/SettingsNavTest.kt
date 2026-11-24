package dev.lemon.rex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavTest {
    private val secondLevel =
        listOf(
            SettingsRoute.Appearance,
            SettingsRoute.FontSize,
            SettingsRoute.Language,
            SettingsRoute.BackgroundAlerts,
            SettingsRoute.About,
            SettingsRoute.Shortcuts,
            SettingsRoute.KeyboardToolbar,
            SettingsRoute.Network,
        )

    @Test
    fun cancelReturnsToTheLevelAbove() {
        val stack = mutableListOf<SettingsRoute>()
        SettingsNav.open(stack, SettingsRoute.Preferences)
        SettingsNav.open(stack, SettingsRoute.Appearance)
        assertEquals(
            listOf<SettingsRoute>(SettingsRoute.Preferences, SettingsRoute.Appearance),
            stack,
        )
        SettingsNav.close(stack)
        assertEquals(listOf<SettingsRoute>(SettingsRoute.Preferences), stack)
        SettingsNav.close(stack)
        assertTrue("closing a root returns to the terminal", stack.isEmpty())
    }

    @Test
    fun everySecondLevelDrawerReturnsToPreferences() {
        for (route in secondLevel) {
            val stack = mutableListOf<SettingsRoute>()
            SettingsNav.open(stack, SettingsRoute.Preferences)
            SettingsNav.open(stack, route)
            SettingsNav.close(stack)
            assertEquals(
                "$route must return to preferences",
                listOf(SettingsRoute.Preferences),
                stack,
            )
        }
    }

    @Test
    fun theProfileEditorIsAThirdLevel() {
        val stack = mutableListOf<SettingsRoute>()
        SettingsNav.open(stack, SettingsRoute.Preferences)
        SettingsNav.open(stack, SettingsRoute.Shortcuts)
        SettingsNav.open(stack, SettingsRoute.ShortcutEditor("shell"))
        SettingsNav.close(stack)
        assertEquals(
            listOf<SettingsRoute>(SettingsRoute.Preferences, SettingsRoute.Shortcuts),
            stack,
        )
        SettingsNav.open(stack, SettingsRoute.ShortcutEditor(""))
        SettingsNav.close(stack)
        assertEquals(
            listOf<SettingsRoute>(SettingsRoute.Preferences, SettingsRoute.Shortcuts),
            stack,
        )
        SettingsNav.close(stack)
        SettingsNav.close(stack)
        assertTrue(stack.isEmpty())
    }

    @Test
    fun anEditorOpenedFromTheTerminalClosesToTheTerminal() {
        val stack = mutableListOf<SettingsRoute>()
        SettingsNav.open(stack, SettingsRoute.ShortcutEditor("neovim"))
        SettingsNav.close(stack)
        assertTrue(stack.isEmpty())
    }

    @Test
    fun reopeningTheVisibleLevelKeepsOneEntry() {
        val stack = mutableListOf<SettingsRoute>()
        SettingsNav.open(stack, SettingsRoute.Preferences)
        SettingsNav.open(stack, SettingsRoute.Preferences)
        assertEquals(1, stack.size)
        SettingsNav.open(stack, SettingsRoute.Shortcuts)
        SettingsNav.open(stack, SettingsRoute.Shortcuts)
        assertEquals(2, stack.size)
        // Returning to a level below the top still nests it under the current drawer.
        SettingsNav.open(stack, SettingsRoute.Preferences)
        assertEquals(3, stack.size)
    }

    @Test
    fun closingAnEmptyStackStaysEmpty() {
        val stack = mutableListOf<SettingsRoute>()
        SettingsNav.close(stack)
        SettingsNav.close(stack)
        assertTrue(stack.isEmpty())
    }
}
