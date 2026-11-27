package dev.lemon.rex

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceSheetTest {
    @get:Rule val ui = createComposeRule()
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val themes = ThemeCatalog.load(app)

    @Test
    fun defaultRemainsFirstAndDarkThemeSelectionDoesNotChangeAppearanceMode() {
        var chosen = ""
        var mode = Appearance.SYSTEM
        ui.setContent {
            MaterialTheme {
                AppearanceSheet(
                    mode,
                    themes,
                    ThemeCatalog.defaults[1],
                    ThemeCatalog.defaults[0],
                    { mode = it },
                    { chosen = it },
                    onClose = {},
                )
            }
        }
        ui.onNodeWithText(appString(R.string.dark_theme)).performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_theme_format, "Rexling Default"))
            .assertIsSelected()
        ui.onNodeWithContentDescription(appString(R.string.a11y_theme_format, "Abyssal Trench"))
            .performClick()
        ui.runOnIdle {
            assertEquals("abyssal-trench", chosen)
            assertEquals(Appearance.SYSTEM, mode)
        }
    }

    @Test
    fun fontSizeChangesHalfAnSpAtATimeForTheSelectedWidth() {
        val sizes =
            mutableStateMapOf(
                TabWidthClass.NARROW to 8f,
                TabWidthClass.MEDIUM to 10f,
                TabWidthClass.WIDE to 12f,
            )
        var current by mutableStateOf(TabWidthClass.MEDIUM)
        ui.setContent {
            MaterialTheme {
                FontSizeDialog(
                    sizes,
                    current,
                    { width, size -> sizes[width] = size },
                    onClose = {},
                )
            }
        }
        ui.onNodeWithText(appString(R.string.tab_width_medium)).assertIsSelected()
        ui.runOnIdle { current = TabWidthClass.WIDE }
        ui.onNodeWithText(
                appString(R.string.font_size_window_format, appString(R.string.tab_width_wide)),
            )
            .assertIsDisplayed()
        ui.onNodeWithText(appString(R.string.tab_width_wide)).assertIsSelected()
        ui.onNodeWithText(appString(R.string.font_size_sp_format, "12")).assertIsDisplayed()
        ui.onNodeWithText(appString(R.string.tab_width_medium)).performClick().assertIsSelected()
        ui.onNodeWithContentDescription(appString(R.string.a11y_increase_font_size)).performClick()
        ui.onNodeWithText(appString(R.string.font_size_sp_format, "10.5")).assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.a11y_decrease_font_size)).performClick()
        ui.onNodeWithText(appString(R.string.font_size_sp_format, "10")).assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.a11y_font_size)).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress,
        ) {
            it(11.5f)
        }
        ui.onNodeWithText(appString(R.string.font_size_sp_format, "11.5")).assertIsDisplayed()
        ui.onNodeWithText(appString(R.string.tab_width_narrow)).performClick().assertIsSelected()
        ui.onNodeWithText(appString(R.string.font_size_sp_format, "8")).assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.a11y_decrease_font_size))
            .assertIsNotEnabled()
        ui.runOnIdle {
            assertEquals(
                mapOf(
                    TabWidthClass.NARROW to 8f,
                    TabWidthClass.MEDIUM to 11.5f,
                    TabWidthClass.WIDE to 12f,
                ),
                sizes.toMap(),
            )
        }
    }

    @Test
    fun lightAndDarkChoicesAndFontSizeSurviveModelRecreation() {
        val name = "rexlink-theme-test-${System.nanoTime()}"
        val store = ViewModelStore()
        fun main(block: () -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        try {
            main {
                app.getSharedPreferences(name, 0)
                    .edit()
                    .putInt("font_size", 12)
                    .putInt("font_size_medium", 13)
                    .apply()
                val first = RexModel(app, name)
                store.put("theme", first)
                assertEquals(
                    mapOf(
                        TabWidthClass.NARROW to 12f,
                        TabWidthClass.MEDIUM to 13f,
                        TabWidthClass.WIDE to 12f,
                    ),
                    first.fontSizes,
                )
                first.chooseTheme("merino-dark")
                first.chooseTheme("merino-light")
                first.changeFontSize(TabWidthClass.NARROW, 11.5f)
                first.changeFontSize(TabWidthClass.WIDE, 21f)
                first.changeAppearance(Appearance.DARK)
                assertTrue(first.arrowJoystickEnabled)
                first.changeArrowJoystick(false)
                assertEquals(ControlShortcutGesture.HOLD, first.shortcutGesture)
                assertFalse(first.toolbarSystemColors)
                first.changeToolbarSystemColors(true)
                first.changeShortcutGesture(ControlShortcutGesture.DOUBLE_TAP)
                val neovim = first.shortcutProfiles.first { it.id == ShortcutProfiles.NEOVIM }
                assertNull(
                    first.saveShortcutProfile(
                        neovim.id,
                        neovim.name,
                        neovim.matchText,
                        neovim.prefix,
                        neovim.enter,
                        ":w\n[Git]\n" + (1..40).joinToString("\n") { "Action $it = <Space>lg" },
                    ),
                )
                assertEquals(NeovimShortcutLayout.CIRCULAR, first.neovimShortcutLayout)
                first.changeNeovimShortcutLayout(NeovimShortcutLayout.GRID)
                assertEquals(NeovimShortcutEffect.FAN_OUT, first.neovimShortcutEffect)
                first.changeNeovimShortcutEffect(NeovimShortcutEffect.SPIN)
                assertTrue(first.neovimShortcutHaptics)
                first.changeNeovimShortcutHaptics(false)
                assertTrue(
                    first.shortcutProfiles.first { it.id == ShortcutProfiles.NEOVIM }.enabled,
                )
                first.enableShortcutProfile(ShortcutProfiles.NEOVIM, false)
                first.moveNeovimShortcut(androidx.compose.ui.geometry.Offset(.7f, .2f))
                val second = RexModel(app, name)
                store.put("theme2", second)
                assertEquals("merino-dark", second.theme(true).id)
                assertEquals("merino-light", second.theme(false).id)
                assertEquals(
                    mapOf(
                        TabWidthClass.NARROW to 11.5f,
                        TabWidthClass.MEDIUM to 13f,
                        TabWidthClass.WIDE to 20f,
                    ),
                    second.fontSizes,
                )
                assertEquals(Appearance.DARK, second.appearance)
                assertFalse(second.arrowJoystickEnabled)
                assertTrue(second.toolbarSystemColors)
                assertEquals(ControlShortcutGesture.DOUBLE_TAP, second.shortcutGesture)
                assertEquals(
                    NeovimShortcuts.parse(
                        ":w\n[Git]\n" + (1..40).joinToString("\n") { "Action $it = <Space>lg" },
                    ),
                    second.shortcutProfiles.first { it.id == ShortcutProfiles.NEOVIM }.items,
                )
                assertEquals(NeovimShortcutLayout.GRID, second.neovimShortcutLayout)
                assertEquals(NeovimShortcutEffect.SPIN, second.neovimShortcutEffect)
                assertFalse(second.neovimShortcutHaptics)
                second.changeNeovimShortcutEffect(NeovimShortcutEffect.NONE)
                val third = RexModel(app, name)
                store.put("theme3", third)
                assertEquals(NeovimShortcutEffect.NONE, third.neovimShortcutEffect)
                app.getSharedPreferences(name, 0)
                    .edit()
                    .putString("neovim_shortcut_effect", "SHUTTER")
                    .apply()
                val fourth = RexModel(app, name)
                store.put("theme4", fourth)
                assertEquals(NeovimShortcutEffect.EXPAND, fourth.neovimShortcutEffect)
                assertFalse(
                    second.shortcutProfiles.first { it.id == ShortcutProfiles.NEOVIM }.enabled,
                )
                assertEquals(
                    androidx.compose.ui.geometry.Offset(.7f, .2f),
                    second.neovimShortcutPosition,
                )
                second.chooseTheme("rexlink-default-dark")
                assertSame(TerminalPalette.Dark, second.theme(true).terminal)
            }
        } finally {
            main { store.clear() }
            app.deleteSharedPreferences(name)
        }
    }

    @Test
    fun quickMenuSwitchUsesCurrentAppearanceAndSupportsBothDirections() {
        val dark = mutableStateOf(false)
        ui.setContent { MaterialTheme { QuickThemeMenuItem(dark.value) { dark.value = it } } }
        ui.onNodeWithText(appString(R.string.toggle_dark_theme)).performClick()
        ui.runOnIdle { assertTrue(dark.value) }
        ui.onNodeWithText(appString(R.string.toggle_light_theme)).performClick()
        ui.runOnIdle { assertFalse(dark.value) }
    }

    @Test
    fun preferencesChooseTheCtrlShortcutGesture() {
        val gesture = mutableStateOf(ControlShortcutGesture.HOLD)
        val joystick = mutableStateOf(true)
        ui.setContent {
            MaterialTheme {
                PreferencesSheet(
                    {},
                    {},
                    {},
                    {},
                    {},
                    gesture.value,
                    { gesture.value = it },
                    arrowJoystickEnabled = joystick.value,
                    onArrowJoystick = { joystick.value = it },
                )
            }
        }
        ui.onNodeWithText(appString(R.string.ctrl_gesture_hold))
            .performScrollTo()
            .assertIsSelected()
        ui.onNodeWithText(appString(R.string.ctrl_gesture_double_tap))
            .performClick()
            .assertIsSelected()
        ui.runOnIdle { assertEquals(ControlShortcutGesture.DOUBLE_TAP, gesture.value) }
        ui.onNodeWithText(appString(R.string.ctrl_gesture_hold)).performClick().assertIsSelected()
        ui.runOnIdle { assertEquals(ControlShortcutGesture.HOLD, gesture.value) }
        ui.onNodeWithText(appString(R.string.use_joystick))
            .performScrollTo()
            .assertIsOn()
            .performClick()
            .assertIsOff()
        ui.onNodeWithText(appString(R.string.use_joystick_detail_off)).assertIsDisplayed()
        ui.runOnIdle { assertFalse(joystick.value) }
    }

    @Test
    fun themedSheetsRenderInLightAndDark() {
        val selected = mutableStateOf(themes.first { it.id == "abyssal-trench" })
        ui.setContent {
            val theme = selected.value
            RexTheme(theme.terminal.dark, theme) {
                Surface(Modifier.fillMaxSize()) {
                    AppearanceSheet(
                        Appearance.SYSTEM,
                        themes,
                        themes.first { it.id == "alpine-milk" },
                        themes.first { it.id == "abyssal-trench" },
                        {},
                        {},
                        onClose = {},
                    )
                }
            }
        }
        for (id in listOf("abyssal-trench", "alpine-milk")) {
            ui.runOnIdle { selected.value = themes.first { it.id == id } }
            ui.waitForIdle()
            ui.onNodeWithText(appString(R.string.appearance)).assertIsDisplayed()
            File(app.cacheDir, "theme-$id.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .takeScreenshot()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
