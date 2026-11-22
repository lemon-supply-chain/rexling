package dev.lemon.rex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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
class CompactTopBarTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun compactHeaderShowsProgramAndDirectoryAndExpandsOnClick() {
        var expandCalls = 0
        ui.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    CompactSessionHeader(
                        title = "nvim",
                        directory = "~/dev/app/rexling",
                        programs = listOf(ProgramIdentity(ProgramKind.NEOVIM, "Neovim")),
                        paneIndex = 0,
                        onExpand = { expandCalls++ },
                    )
                }
            }
        }
        val node = ui.onNodeWithContentDescription("nvim - ~/dev/app/rexling")
        node.assertIsDisplayed()
        node.performClick()
        ui.runOnIdle { assertEquals(1, expandCalls) }
    }

    @Test
    fun sessionHeaderWithCollapseButtonCollapsesOnClick() {
        var collapseCalls = 0
        ui.setContent {
            MaterialTheme {
                Box(Modifier.width(340.dp)) {
                    SessionHeader(
                        sessions = listOf(SessionItem("a", "alpha · Mac")),
                        selectedId = "a",
                        connected = true,
                        enabled = true,
                        programs = listOf(ProgramIdentity(ProgramKind.NEOVIM, "Neovim")),
                        paneIndex = 0,
                        directory = "~/dev/app/rexling",
                        onSessions = {},
                        onSelect = {},
                        onLast = {},
                        title = "nvim",
                        onCollapse = { collapseCalls++ },
                        menu = {},
                    )
                }
            }
        }
        val collapseBtn = ui.onNodeWithContentDescription("Collapse top app bar")
        collapseBtn.assertIsDisplayed()
        collapseBtn.performClick()
        ui.runOnIdle { assertEquals(1, collapseCalls) }
    }
}
