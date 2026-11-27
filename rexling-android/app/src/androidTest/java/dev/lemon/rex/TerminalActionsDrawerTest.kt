package dev.lemon.rex

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.positionInRoot
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
                                TerminalDrawerAction(
                                    appString(R.string.paste),
                                    ChromeIcon.PASTE,
                                    enabled = false,
                                ) {},
                                TerminalDrawerAction(
                                    appString(R.string.image),
                                    ChromeIcon.IMAGE,
                                    enabled = false,
                                ) {},
                                TerminalDrawerAction(
                                    appString(R.string.attach_file),
                                    ChromeIcon.FILE,
                                    enabled = false,
                                ) {},
                                TerminalDrawerAction(
                                    appString(R.string.copy_viewport),
                                    ChromeIcon.COPY,
                                ) {},
                                TerminalDrawerAction(
                                    appString(R.string.light_theme),
                                    ChromeIcon.SUN,
                                ) {},
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
        val status =
            ui.onNodeWithText("Viewing terminal")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .layoutInfo
                .coordinates
        val dimensions =
            ui.onNodeWithText("74 × 45")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .layoutInfo
                .coordinates
        assertEquals(
            status.positionInRoot().y + status[FirstBaseline],
            dimensions.positionInRoot().y + dimensions[FirstBaseline],
            1f,
        )
        assertTrue(status.positionInRoot().x + status.size.width <= dimensions.positionInRoot().x)
        ui.onNodeWithText(appString(R.string.paste)).assertIsNotEnabled()
        ui.onNodeWithText(appString(R.string.attach_file)).assertIsDisplayed().assertIsNotEnabled()
        val theme =
            ui.onNodeWithText(appString(R.string.light_theme))
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val copy =
            ui.onNodeWithText(appString(R.string.copy_viewport))
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(theme.left >= copy.right)
        ui.onNodeWithText(appString(R.string.close_pane)).performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.close_terminal_menu)).assertIsDisplayed()
        ui.onNodeWithContentDescription(appString(R.string.preferences)).assertIsDisplayed()
        ui.onNodeWithText(appString(R.string.close_pane)).performClick()
        ui.runOnIdle { assertEquals(listOf("dismiss", "close pane"), events) }
    }
}
