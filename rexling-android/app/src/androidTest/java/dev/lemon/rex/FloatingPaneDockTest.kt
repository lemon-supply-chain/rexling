package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingPaneDockTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun dockOpensSwitchesAndMinimizesWithoutRemovingOtherIcons() {
        val active = mutableStateOf<String?>(null)
        val panes = listOf(BlockItem("vim", "Editor"), BlockItem("shell", "Logs"))
        val programs =
            mapOf(
                "vim" to ProgramIdentity(ProgramKind.NEOVIM, "nvim"),
                "shell" to ProgramIdentity.Terminal,
            )
        var minimized = 0
        var capturing = false
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(Modifier.fillMaxSize()) {
                    FloatingPaneDock(
                        panes,
                        active.value,
                        programs,
                        emptyMap(),
                        true,
                        { active.value = it },
                        {
                            active.value = null
                            minimized++
                        },
                        Modifier.align(Alignment.BottomEnd),
                        directories = mapOf("vim" to "~/Dev/rexling", "shell" to "~/Dev/logs"),
                        onExpandedChange = { capturing = it },
                    )
                }
            }
        }
        ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "floating-stack-light.png",
                )
                .outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Editor"))
            .assertDoesNotExist()
        ui.onNodeWithText("Editor · ~/Dev/rexling").assertDoesNotExist()
        ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes))
            .assertIsNotSelected()
        val anchor =
            ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes))
                .fetchSemanticsNode()
                .boundsInRoot
        ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes)).performClick()
        ui.onNodeWithText("Editor · ~/Dev/rexling").assertIsDisplayed()
        ui.runOnIdle { assertTrue(capturing) }
        ui.onNodeWithText("Logs · ~/Dev/logs").assertIsDisplayed()
        assertEquals(
            anchor,
            ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes))
                .fetchSemanticsNode()
                .boundsInRoot,
        )
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Editor"))
            .performClick()
        ui.onNodeWithContentDescription(
                appString(R.string.a11y_minimize_floating_pane_named, "Editor"),
            )
            .assertDoesNotExist()
        ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes)).assertIsSelected()
        ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes)).performClick()
        ui.onNodeWithContentDescription(
                appString(R.string.a11y_minimize_floating_pane_named, "Editor"),
            )
            .assertIsSelected()
        ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "floating-dock-light.png",
                )
                .outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Logs"))
            .performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes)).performClick()
        ui.onNodeWithContentDescription(
                appString(R.string.a11y_minimize_floating_pane_named, "Logs"),
            )
            .assertIsSelected()
            .performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes)).performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Editor"))
            .assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Logs"))
            .assertIsDisplayed()
        ui.runOnIdle {
            assertNull(active.value)
            assertEquals(1, minimized)
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes)).performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Editor"))
            .assertDoesNotExist()
        ui.mainClock.advanceTimeBy(200)
        ui.runOnIdle { assertFalse(capturing) }
    }

    @Test
    fun singleFloatingPaneOpensDirectlyAndMinimizesWithoutAMenu() {
        val active = mutableStateOf<String?>(null)
        var minimized = 0
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                Box(Modifier.fillMaxSize()) {
                    FloatingPaneDock(
                        listOf(BlockItem("one", "Editor")),
                        active.value,
                        mapOf("one" to ProgramIdentity(ProgramKind.NEOVIM, "nvim")),
                        emptyMap(),
                        true,
                        { active.value = it },
                        {
                            active.value = null
                            minimized++
                        },
                        Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }
        ui.onNodeWithContentDescription(appString(R.string.a11y_floating_panes))
            .assertDoesNotExist()
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Editor"))
            .performClick()
        ui.onNodeWithContentDescription(
                appString(R.string.a11y_minimize_floating_pane_named, "Editor"),
            )
            .performClick()
        ui.onNodeWithContentDescription(appString(R.string.a11y_open_floating_pane, "Editor"))
            .assertIsDisplayed()
        ui.runOnIdle {
            assertNull(active.value)
            assertEquals(1, minimized)
        }
    }

    @Test
    fun preferenceCanBeEnabledAndDisabled() {
        val enabled = mutableStateOf(false)
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                PreferencesSheet(
                    {},
                    {},
                    {},
                    {},
                    {},
                    minimizeFloatingPanes = enabled.value,
                    onMinimizeFloatingPanes = { enabled.value = it },
                )
            }
        }
        val toggle = ui.onNode(hasText("Minimize floating panes") and isToggleable())
        toggle
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
            .performClick()
            .assertIsOff()
    }
}
