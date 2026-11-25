package dev.lemon.rex

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated settings previews: never connect to or resize a user's Rex pane. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class SettingsScreensTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun settingsRemainReadableInBothThemes() {
        var dark by mutableStateOf(false)
        ui.setContent {
            RexTheme(dark, ThemeCatalog.defaults[if (dark) 0 else 1]) {
                // Match the workspace: no parent Surface providing a text color.
                PreferencesSheet({}, {}, {}, {}, {})
            }
        }
        for (isDark in listOf(false, true)) {
            ui.runOnIdle { dark = isDark }
            var layout: androidx.compose.ui.text.TextLayoutResult? = null
            ui.onNodeWithContentDescription(appString(R.string.close_preferences))
                .assertIsDisplayed()
            ui.onNodeWithText(appString(R.string.preferences))
                .assertIsDisplayed()
                .performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
                ) { action ->
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    action(results)
                    layout = results.first()
                }
            val luminance = layout!!.layoutInput.style.color.luminance()
            assertTrue(
                "Preference header must contrast with its background: dark=$isDark luminance=$luminance",
                if (isDark) luminance > .5f else luminance < .3f,
            )
            ui.onNodeWithText(appString(R.string.background_alerts))
                .performScrollTo()
                .assertIsDisplayed()
            ui.onNodeWithText("Portside").assertDoesNotExist()
            capture("preferences", isDark)
        }
    }

    @Test
    fun expandedPreferencesSheetOnlyDismissesFromTopHandleZone() {
        var closed = 0
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                PreferencesSheet({}, {}, {}, {}, onClose = { closed++ })
            }
        }
        val handleZone = ui.onNodeWithTag("Preferences handle zone")
        ui.awaitDisplayed(handleZone)
        handleZone.performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 600f, durationMillis = 150)
        }
        ui.waitForIdle()
        val expandedTop = handleZone.fetchSemanticsNode().boundsInRoot.top
        ui.onNodeWithText(appString(R.string.appearance)).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 500f, durationMillis = 150)
        }
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(0, closed) }
        assertEquals(expandedTop, handleZone.fetchSemanticsNode().boundsInRoot.top, 2f)

        val bodyArea = ui.onNodeWithTag("Preferences body area")
        bodyArea.performTouchInput {
            swipeDown(
                startY = top + 120f,
                endY = top + 620f,
                durationMillis = 150,
            )
            // Swipe along the horizontal margin / blank edge too.
        }
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(0, closed) }
        assertEquals(expandedTop, handleZone.fetchSemanticsNode().boundsInRoot.top, 2f)

        bodyArea.performTouchInput {
            val marginStart = Offset(left + 4f, top + 120f)
            down(marginStart)
            moveBy(Offset(0f, 400f))
            up()
        }
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(0, closed) }
        assertEquals(expandedTop, handleZone.fetchSemanticsNode().boundsInRoot.top, 2f)

        bodyArea.performTouchInput {
            val bottomBlankStart = Offset(centerX, (bottom - 8f).coerceAtLeast(top + 20f))
            down(bottomBlankStart)
            moveBy(Offset(0f, 300f))
            up()
        }
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(0, closed) }
        assertEquals(expandedTop, handleZone.fetchSemanticsNode().boundsInRoot.top, 2f)

        handleZone.performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 100)
        }
        ui.waitForIdle()
        assertTrue(
            closed > 0 || handleZone.fetchSemanticsNode().boundsInRoot.top > expandedTop + 20f,
        )
    }

    @Test
    fun drawerHandleLightsUpWhenPullingDownFromTop() {
        var actionArgb = 0
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                actionArgb = MaterialTheme.colorScheme.roles.action.toArgb()
                PreferencesSheet({}, {}, {}, {}, onClose = {})
            }
        }
        val handleZone = ui.onNodeWithTag("Preferences handle zone")
        val drawerHandle = ui.onNodeWithTag("Preferences drawer handle")
        ui.awaitDisplayed(handleZone)
        handleZone.performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 600f, durationMillis = 150)
        }
        ui.waitForIdle()

        fun countActionPixels(bitmap: Bitmap): Int {
            var count = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getPixel(x, y) == actionArgb) count++
                }
            }
            return count
        }

        assertEquals(0, countActionPixels(drawerHandle.captureToImage().asAndroidBitmap()))

        handleZone.performTouchInput {
            down(center)
            moveBy(Offset(0f, 80f))
        }
        ui.waitForIdle()
        ui.waitUntil(2_000) {
            countActionPixels(drawerHandle.captureToImage().asAndroidBitmap()) > 0
        }

        // Pulling back above the start position clears the highlight while still pressed.
        handleZone.performTouchInput { moveBy(Offset(0f, -120f)) }
        ui.waitForIdle()
        ui.waitUntil(2_000) {
            countActionPixels(drawerHandle.captureToImage().asAndroidBitmap()) == 0
        }

        handleZone.performTouchInput { cancel() }
        ui.waitForIdle()
        ui.waitUntil(2_000) {
            countActionPixels(drawerHandle.captureToImage().asAndroidBitmap()) == 0
        }
    }

    @Test
    fun systemBackClosesTheExpandedPreferencesSheet() {
        var closed = 0
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                PreferencesSheet({}, {}, {}, {}, onClose = { closed++ })
            }
        }
        val handleZone = ui.onNodeWithTag("Preferences handle zone")
        ui.awaitDisplayed(handleZone)
        handleZone.performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 600f, durationMillis = 150)
        }
        ui.waitForIdle()
        // Delivered synchronously so the event cannot leak into the next test's sheet.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000) { closed > 0 }
        ui.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun editingOneTabProfileDoesNotChangeTheCurrentProfile() {
        val layouts =
            mutableStateMapOf(
                TabWidthClass.MEDIUM to TabBarLayout(TabBarPosition.LEFT),
                TabWidthClass.WIDE to TabBarLayout(),
            )
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                PreferencesSheet(
                    {},
                    {},
                    {},
                    {},
                    {},
                    tabLayouts = layouts,
                    tabWidth = TabWidthClass.MEDIUM to 555,
                    onTabLayout = { width, layout -> layouts[width] = layout },
                )
            }
        }
        val medium = ui.onNodeWithText(appString(R.string.tab_width_medium))
        val wide = ui.onNodeWithText(appString(R.string.tab_width_wide))
        val expanded =
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                appString(R.string.a11y_state_expanded),
            )
        val collapsed =
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                appString(R.string.a11y_state_collapsed),
            )
        medium.performScrollTo().assert(expanded)
        ui.onNode(
                hasText(appString(R.string.tab_width_medium)) and
                    hasText(appString(R.string.tab_width_now)),
            )
            .assertExists()
        ui.onNodeWithText(appString(R.string.tab_position_left))
            .performScrollTo()
            .assertIsSelected()
        ui.onNodeWithText(
                appString(
                    R.string.tab_width_current_range_format,
                    appString(R.string.tab_width_medium_range),
                    555,
                ),
            )
            .performScrollTo()
            .assertIsDisplayed()
        medium.performScrollTo().performClick().assert(collapsed)
        ui.onNode(
                hasText(appString(R.string.tab_width_medium)) and
                    hasText(
                        appString(
                            R.string.tab_summary_side_format,
                            appString(R.string.tab_position_left),
                            appString(R.string.tab_rail_style_icons).lowercase(),
                        ),
                    ),
            )
            .assertExists()
        ui.onNodeWithText(appString(R.string.position_label)).assertDoesNotExist()
        wide.performScrollTo().performClick().assert(expanded)
        ui.onNode(
                hasText(appString(R.string.tab_width_medium)) and
                    hasText(appString(R.string.tab_width_now)),
            )
            .assertExists()
        ui.onNodeWithText(appString(R.string.tab_position_bottom))
            .performScrollTo()
            .assertIsSelected()
        ui.onNodeWithText(appString(R.string.sidebar_style)).assertDoesNotExist()
        ui.onNodeWithText(appString(R.string.tab_position_right)).performClick()
        ui.onNodeWithText(appString(R.string.tab_rail_style_titles))
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        medium.performScrollTo().performClick().assert(expanded)
        ui.onNodeWithText(appString(R.string.tab_position_left))
            .performScrollTo()
            .assertIsSelected()
        ui.onNode(
                hasText(appString(R.string.tab_width_wide)) and
                    hasText(
                        appString(
                            R.string.tab_summary_side_format,
                            appString(R.string.tab_position_right),
                            appString(R.string.tab_rail_style_titles).lowercase(),
                        ),
                    ),
            )
            .assertExists()
        val profiles =
            ui.onNodeWithTag("Tab width profiles")
                .performScrollTo()
                .captureToImage()
                .asAndroidBitmap()
        File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "tab-width-profiles.png",
            )
            .outputStream()
            .use { profiles.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ui.runOnIdle {
            assertEquals(TabBarLayout(TabBarPosition.LEFT), layouts[TabWidthClass.MEDIUM])
            assertEquals(
                TabBarLayout(TabBarPosition.RIGHT, TabRailStyle.TITLES),
                layouts[TabWidthClass.WIDE],
            )
        }
    }

    @Test
    fun paneProfilesInheritAndSaveChoicesIndependently() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val name = "pane-profile-test-${System.nanoTime()}"
        val prefs = app.getSharedPreferences(name, 0)
        prefs.edit(commit = true) { putBoolean("multi_pane_unfolded", true) }
        val store = ViewModelStore()
        lateinit var model: RexModel
        ui.runOnUiThread {
            model = RexModel(app, name)
            store.put("panes", model)
        }
        var windowOrientation by mutableIntStateOf(Configuration.ORIENTATION_PORTRAIT)
        try {
            ui.setContent {
                RexTheme(false, ThemeCatalog.defaults[1]) {
                    val configuration =
                        Configuration(LocalConfiguration.current).apply {
                            orientation = windowOrientation
                        }
                    CompositionLocalProvider(LocalConfiguration provides configuration) {
                        PreferencesSheet(
                            {},
                            {},
                            {},
                            {},
                            {},
                            paneLayouts = model.paneLayouts,
                            onPaneLayout = model::changePaneLayout,
                        )
                    }
                }
            }
            val selectable = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
            val single = ui.onNode(hasText(appString(R.string.single_pane)) and selectable)
            val multiple = ui.onNode(hasText(appString(R.string.multiple_panes)) and selectable)
            ui.onNode(
                    hasText(appString(R.string.pane_orientation_portrait)) and
                        hasText(appString(R.string.tab_width_now)),
                )
                .performScrollTo()
                .assertIsDisplayed()
            ui.onNode(
                    hasText(appString(R.string.pane_orientation_landscape)) and
                        hasText(appString(R.string.multiple_panes)),
                )
                .assertExists()
            multiple.performScrollTo().assertIsSelected()
            ui.onNodeWithText(appString(R.string.pane_orientation_landscape))
                .performScrollTo()
                .performClick()
            single.performScrollTo().performClick().assertIsSelected()
            ui.onNode(
                    hasText(appString(R.string.pane_orientation_portrait)) and
                        hasText(appString(R.string.tab_width_now)) and
                        hasText(appString(R.string.multiple_panes)),
                )
                .assertExists()
            ui.onNodeWithText(appString(R.string.pane_orientation_portrait))
                .performScrollTo()
                .performClick()
            multiple.performScrollTo().assertIsSelected()

            ui.runOnIdle { windowOrientation = Configuration.ORIENTATION_LANDSCAPE }
            ui.onNode(
                    hasText(appString(R.string.pane_orientation_landscape)) and
                        hasText(appString(R.string.tab_width_now)),
                )
                .performScrollTo()
                .assertIsDisplayed()
            single.performScrollTo().assertIsSelected()
            ui.runOnIdle {
                val reopened = RexModel(app, name).also { store.put("panes-reopened", it) }
                assertEquals(
                    mapOf(PaneOrientation.PORTRAIT to true, PaneOrientation.LANDSCAPE to false),
                    reopened.paneLayouts,
                )
                assertFalse(prefs.contains(PaneOrientation.PORTRAIT.preferenceKey))
            }
        } finally {
            ui.runOnUiThread { store.clear() }
            app.deleteSharedPreferences(name)
        }
    }

    @Test
    fun compactTopBarAndCtrlShortcutUseSegmentedChoices() {
        var mode by mutableStateOf(CompactHeaderMode.NEVER)
        var gesture by mutableStateOf(ControlShortcutGesture.HOLD)
        ui.setContent {
            MaterialTheme {
                PreferencesSheet(
                    {},
                    {},
                    {},
                    {},
                    {},
                    shortcutGesture = gesture,
                    onShortcutGesture = { gesture = it },
                    compactHeaderMode = mode,
                    onCompactHeaderMode = { mode = it },
                )
            }
        }
        ui.onNodeWithText(appString(R.string.compact_header_never))
            .performScrollTo()
            .assertIsSelected()
        listOf(
                CompactHeaderMode.ALWAYS,
                CompactHeaderMode.WITH_KEYBOARD,
                CompactHeaderMode.NEVER,
            )
            .forEach { next ->
                ui.onNodeWithText(appString(next.labelRes))
                    .performScrollTo()
                    .performClick()
                    .assertIsSelected()
                ui.runOnIdle { assertEquals(next, mode) }
            }
        ui.onNodeWithText(appString(R.string.ctrl_gesture_hold))
            .performScrollTo()
            .assertIsSelected()
        ui.onNodeWithText(appString(R.string.ctrl_gesture_double_tap))
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        ui.runOnIdle { assertEquals(ControlShortcutGesture.DOUBLE_TAP, gesture) }
    }

    @Test
    fun toolbarSystemColorsAreOptIn() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        var enabled by mutableStateOf(false)
        ui.setContent {
            MaterialTheme {
                PreferencesSheet(
                    {},
                    {},
                    {},
                    {},
                    {},
                    toolbarSystemColors = enabled,
                    onToolbarSystemColors = { enabled = it },
                )
            }
        }
        val setting = ui.onNodeWithText(appString(R.string.toolbar_system_colors)).performScrollTo()
        setting.assertIsOff().performClick().assertIsOn()
        setting.performClick().assertIsOff()
    }

    @Test
    fun scrollingTheDrawerContentCannotDismissIt() {
        var open by mutableStateOf(true)
        ui.setContent {
            MaterialTheme { if (open) PreferencesSheet({}, {}, {}, {}, onClose = { open = false }) }
        }
        val content = ui.onNode(hasScrollAction(), useUnmergedTree = true)
        ui.onNodeWithText(appString(R.string.about_rexling)).performScrollTo()
        repeat(4) { content.performTouchInput { swipeDown() } }
        ui.onNodeWithText(appString(R.string.appearance)).assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.close_preferences)).assertIsDisplayed()
        val heading = ui.onNodeWithText(appString(R.string.preferences))
        val handle = ui.onNodeWithContentDescription(appString(R.string.a11y_drawer_handle))
        val restingTop = heading.fetchSemanticsNode().boundsInRoot.top
        val shortPull = with(ui.density) { 28.dp.toPx() }
        handle.performTouchInput {
            down(center)
            moveBy(Offset(0f, shortPull))
        }
        val movedTop = heading.fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "The entire drawer should move before the finger is released",
            movedTop > restingTop + shortPull / 2,
        )
        handle.performTouchInput { up() }
        ui.waitForIdle()
        assertEquals(
            "A short pull should return the drawer to rest",
            restingTop,
            heading.fetchSemanticsNode().boundsInRoot.top,
            1f,
        )

        val pull = with(ui.density) { 88.dp.toPx() }
        handle.performTouchInput {
            down(center)
            moveBy(Offset(0f, pull))
            cancel()
        }
        ui.waitForIdle()
        assertEquals(
            "Cancelling a pull should restore the drawer",
            restingTop,
            heading.fetchSemanticsNode().boundsInRoot.top,
            1f,
        )
        handle.performTouchInput { swipe(center, center + Offset(0f, pull)) }
        ui.waitUntil(5000) { !open }
        ui.runOnIdle { open = true }
        ui.onNodeWithContentDescription(appString(R.string.close_preferences)).performClick()
        ui.waitUntil(5000) { !open }
    }

    @Test
    fun closingSecondLevelDrawerKeepsPreferencesScrollPosition() {
        var stackSize by mutableIntStateOf(1)
        var secondLevelLayered by mutableStateOf(false)
        ui.setContent {
            val stack = remember { mutableStateListOf<SettingsRoute>(SettingsRoute.Preferences) }
            LaunchedEffect(stack.size) { stackSize = stack.size }
            RexTheme(false, ThemeCatalog.defaults[1]) {
                stack.forEachIndexed { index, route ->
                    key(index, route) {
                        CompositionLocalProvider(LocalDrawerLayered provides (index > 0)) {
                            when (route) {
                                SettingsRoute.Preferences ->
                                    PreferencesSheet(
                                        {},
                                        {},
                                        {},
                                        onAbout = { SettingsNav.open(stack, SettingsRoute.About) },
                                        onClose = { SettingsNav.close(stack, index, route) },
                                    )
                                SettingsRoute.About -> {
                                    secondLevelLayered = LocalDrawerLayered.current
                                    AboutSheet { SettingsNav.close(stack, index, route) }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
        val aboutRow = ui.onNodeWithText(appString(R.string.about_rexling))
        aboutRow.performScrollTo().assertIsDisplayed()
        val beforeTop = aboutRow.fetchSemanticsNode().boundsInRoot.top

        aboutRow.performClick()
        ui.waitUntil(5_000) { stackSize == 2 }
        val closeAbout = ui.onNodeWithContentDescription(appString(R.string.close_about))
        ui.awaitDisplayed(closeAbout)
        assertTrue(secondLevelLayered)

        closeAbout.performClick()
        ui.waitUntil(5_000) { stackSize == 1 }
        ui.waitForIdle()
        aboutRow.assertIsDisplayed()
        val afterTop = aboutRow.fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "PreferencesSheet scroll position should be preserved (before=$beforeTop, after=$afterTop)",
            abs(afterTop - beforeTop) <= 2f,
        )
    }

    private fun capture(name: String, dark: Boolean) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Allow the platform dialog/window animation to reach the screenshot surface.
        Thread.sleep(300)
        val large = instrumentation.targetContext.resources.configuration.fontScale > 1.2f
        File(
                instrumentation.targetContext.cacheDir,
                "settings-$name-${if(dark) "dark" else "light"}${if(large) "-large-text" else ""}.png",
            )
            .outputStream()
            .use {
                instrumentation.uiAutomation
                    .takeScreenshot()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
    }
}
