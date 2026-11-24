package dev.lemon.rex

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalActionsDrawerTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun actionsScrollWhileHeaderStaysVisible() {
        val open = mutableStateOf(true)
        val events = mutableListOf<String>()
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                if (open.value)
                    TerminalActionsDrawer(
                        title = "nvim",
                        subtitle = "Work · Shipyard",
                        status = "Viewing terminal",
                        dimensions = 74 to 45,
                        controlled = false,
                        ownership = {},
                        quickActions =
                            listOf(
                                TerminalDrawerAction("Paste", ChromeIcon.PASTE, enabled = false) {},
                                TerminalDrawerAction("Image", ChromeIcon.IMAGE, enabled = false) {},
                                TerminalDrawerAction(
                                    "Attach file",
                                    ChromeIcon.FILE,
                                    enabled = false,
                                ) {},
                                TerminalDrawerAction("Copy viewport", ChromeIcon.COPY) {},
                                TerminalDrawerAction("Light theme", ChromeIcon.SUN) {},
                            ),
                        actions =
                            listOf(
                                TerminalDrawerAction(
                                    "New session",
                                    ChromeIcon.ADD,
                                    opensDrawer = true,
                                ) {},
                                TerminalDrawerAction(
                                    "Port forwarding",
                                    ChromeIcon.SERVER,
                                    opensDrawer = true,
                                ) {},
                                TerminalDrawerAction(
                                    "Servers",
                                    ChromeIcon.SERVER,
                                    opensDrawer = true,
                                ) {},
                                TerminalDrawerAction("Refresh sessions", ChromeIcon.REFRESH) {},
                            ),
                        onPreferences = {},
                        onClosePane = { events += "close pane" },
                        onDismiss = {
                            open.value = false
                            events += "dismiss"
                        },
                    )
            }
        }

        ui.onNodeWithText("nvim").assertIsDisplayed()
        ui.onNodeWithText("74 × 45").assertIsDisplayed()
        ui.onNodeWithText("Paste").assertIsNotEnabled()
        ui.onNodeWithText("Attach file").assertIsDisplayed().assertIsNotEnabled()
        val theme =
            ui.onNodeWithText("Light theme").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val copy =
            ui.onNodeWithText("Copy viewport").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(theme.left >= copy.right)
        ui.onNodeWithText("Close pane").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("Close terminal menu").assertIsDisplayed()
        ui.onNodeWithContentDescription("Preferences").assertIsDisplayed()
        ui.onNodeWithText("Close pane").performClick()
        ui.runOnIdle { assertEquals(listOf("dismiss", "close pane"), events) }
    }
}
