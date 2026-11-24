package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TerminalProgressUiTest {
    @get:Rule val ui = createComposeRule()

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ui.waitForIdle()
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            instrumentation.uiAutomation
                .takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun tabProgressUpdatesStateAndClearsWithoutChangingTheTitle() {
        var report by mutableStateOf<TerminalProgress?>(TerminalProgress("set", 40))
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                Box(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) {
                    TerminalTabs(
                        listOf(WindowItem("w", "Cargo build", listOf(BlockItem("b", "cargo")))),
                        "w",
                        true,
                        {},
                        panePrograms = { listOf(ProgramIdentity.resolve(listOf("cargo"))) },
                        selectedPane = { 0 },
                        paneProgress = { report },
                        actions = {},
                    )
                }
            }
        }
        // A determinate report shows its percentage; the state word replaces it otherwise.
        fun described(@androidx.annotation.StringRes id: Int, vararg args: Any) =
            ui.onNodeWithContentDescription(appString(id, *args), substring = true)
        described(R.string.a11y_progress_percent, 40).assertIsDisplayed()
        capture("progress-tab-light.png")
        ui.runOnIdle { report = TerminalProgress("pause", 40) }
        described(R.string.a11y_progress_paused).assertIsDisplayed()
        ui.runOnIdle { report = TerminalProgress("error", 40) }
        described(R.string.a11y_progress_failed).assertIsDisplayed()
        ui.runOnIdle { report = TerminalProgress("indeterminate", null) }
        described(R.string.a11y_progress_working).assertIsDisplayed()
        ui.runOnIdle { report = null }
        described(R.string.a11y_progress_percent, 40).assertDoesNotExist()
        ui.onNodeWithText("Cargo build").assertIsDisplayed()
    }

    @Test
    fun activityShowsPercentageWithoutMarkSeenForProgressOnlyRows() {
        val record =
            ActivityRecord.parse(
                JSONObject(
                    """{"session":"s","sessionLabel":"Build session","windowLabel":"Build","block":"b","id":"osc9-progress","title":"Cargo build","app":"cargo","state":"working","progressOnly":true,"progress":40,"terminalProgress":{"state":"set","progress":40}}""",
                ),
            )!!
        val server = ServerProfile.create("Mac", "https://mac.example", "mac")
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                ActivitySheet(
                    listOf(ActivitySource(server, true, false, "", false, listOf(record))),
                    {},
                    {},
                    {},
                    initialFilter = ActivityFilter.ALL,
                )
            }
        }
        // The percentage rides on the row's state description rather than a text node.
        val rowDescription =
            appString(
                R.string.a11y_activity_record,
                "Cargo build",
                TerminalProgress("set", 40).label,
                "Build session",
                "Mac",
            )
        val row =
            ui.onNodeWithContentDescription(rowDescription, useUnmergedTree = true)
                .fetchSemanticsNode()
        val state: String =
            row.config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
        assertTrue("state=$state", state.contains("40%"))
        ui.onNodeWithText(appString(R.string.mark_seen)).assertIsNotEnabled()
        capture("progress-activity-dark.png")
    }
}
