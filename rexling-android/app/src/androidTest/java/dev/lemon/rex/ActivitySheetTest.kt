package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivitySheetTest {
    @get:Rule val ui = createComposeRule()

    private fun record(state: String, title: String, session: String, block: String) =
        ActivityRecord.parse(
            JSONObject()
                .put("session", session)
                .put("sessionLabel", "Session $session")
                .put("windowLabel", "Tab")
                .put("block", block)
                .put("state", state)
                .put("kind", "permission")
                .put("title", title)
                .put("app", "codex")
                .put("message", "Reported message"),
        )!!

    private fun capture(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            instrumentation.uiAutomation
                .takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun activityIsGroupedAndSelectingPreservesServerAndPaneIdentity() {
        val a = ServerProfile.create("Linux", "https://linux.example", "a")
        val b = ServerProfile.create("Mac", "https://mac.example", "b")
        val first = record("working", "Building", "s1", "pane1")
        val second = record("blocked", "Review", "s2", "pane2")
        var selected: ActivityTarget? = null
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                ActivitySheet(
                    listOf(
                        ActivitySource(a, true, false, "", false, listOf(first)),
                        ActivitySource(b, true, false, "", false, listOf(second)),
                    ),
                    {},
                    { selected = it },
                    {},
                    initialFilter = ActivityFilter.ALL,
                )
            }
        }
        ui.onNodeWithText("Linux").assertIsDisplayed()
        ui.onNodeWithText("Mac").assertIsDisplayed()
        capture("activity-compact-light.png")
        ui.onNodeWithContentDescription(appString(R.string.refresh_activity)).assertIsEnabled()
        ui.onNodeWithText(appString(R.string.filter_attention)).performClick()
        ui.onNodeWithText("Building").assertDoesNotExist()
        ui.onNodeWithContentDescription(
                appString(
                    R.string.a11y_activity_record,
                    "Review",
                    appString(R.string.activity_kind_approval),
                    "Session s2",
                    "Mac",
                ),
            )
            .performClick()
        ui.runOnIdle {
            assertEquals("b", selected!!.server.id)
            assertEquals("s2", selected!!.record.session)
            assertEquals("pane2", selected!!.record.block)
        }
    }

    @Test
    fun tabButtonOpensTheSwitcherDirectlyAndKeepsActivityRightmost() {
        val calls = mutableListOf<String>()
        ui.setContent {
            MaterialTheme {
                TerminalActions(true, true, { calls += "activity" }, { calls += "switcher" })
            }
        }
        val pane =
            ui.onNodeWithContentDescription(appString(R.string.a11y_tabs_and_panes))
                .fetchSemanticsNode()
                .boundsInRoot
        val eye =
            ui.onNodeWithContentDescription(appString(R.string.activity))
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(eye.left >= pane.right)
        ui.onNodeWithContentDescription(appString(R.string.a11y_tabs_and_panes)).performClick()
        ui.runOnIdle { assertEquals(listOf("switcher"), calls) }
        ui.onNodeWithText("Switch pane").assertDoesNotExist()
        ui.onNodeWithText("New pane").assertDoesNotExist()
        ui.onNodeWithText(appString(R.string.label_new_tab)).assertDoesNotExist()
        ui.onNodeWithContentDescription(appString(R.string.activity)).performClick()
        ui.runOnIdle { assertEquals("activity", calls.last()) }
    }

    @Test
    fun staleActivityIsDisabledAndShowsItsRefreshError() {
        val server = ServerProfile.create("Linux", "https://linux.example", "a")
        var selected = false
        val record = record("done", "Finished task", "s", "p")
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                ActivitySheet(
                    listOf(
                        ActivitySource(
                            server,
                            false,
                            false,
                            "Connection interrupted",
                            false,
                            listOf(record),
                        ),
                    ),
                    {},
                    { selected = true },
                    {},
                    initialFilter = ActivityFilter.ALL,
                )
            }
        }
        ui.onNodeWithText("Connection interrupted").assertIsDisplayed()
        ui.onNodeWithContentDescription(
                appString(
                    R.string.a11y_activity_record,
                    "Finished task",
                    appString(R.string.filter_finished),
                    "Session s",
                    "Linux",
                ),
            )
            .assertIsNotEnabled()
        capture("activity-compact-dark.png")
        ui.runOnIdle { assertFalse(selected) }
    }

    @Test
    fun markingAnItemSeenDoesNotOpenItsTerminal() {
        val server = ServerProfile.create("Linux", "https://linux.example", "a")
        val task = record("done", "Review", "s", "p").copy(message = "")
        val older = record("done", "Earlier task", "s", "older").copy(message = "")
        val seen = androidx.compose.runtime.mutableStateOf(setOf(older.key))
        var opened = 0
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                ActivitySheet(
                    listOf(
                        ActivitySource(
                            server,
                            true,
                            false,
                            "",
                            false,
                            listOf(task, older),
                            seen.value,
                        ),
                    ),
                    {},
                    { opened++ },
                    {},
                    onSeen = { seen.value += it.record.key },
                    initialFilter = ActivityFilter.ALL,
                )
            }
        }
        val rowDescription =
            appString(
                R.string.a11y_activity_record,
                "Review",
                appString(R.string.filter_finished),
                "Session s",
                "Linux",
            )
        val title = ui.onNodeWithText("Review", useUnmergedTree = true)
        val context =
            ui.onNode(
                hasText("Session s · Tab") and
                    hasAnyAncestor(hasContentDescription(rowDescription)),
                useUnmergedTree = true,
            )
        ui.onNodeWithText(appString(R.string.activity), useUnmergedTree = true)
            .assertPaneTitleTypography()
        ui.onNodeWithText(appString(R.string.filter_attention), useUnmergedTree = true)
            .assertPaneTitleTypography()
        title.assertPaneTitleTypography()
        context.assertPaneDetailTypography()
        fun textBounds() =
            title
                .fetchSemanticsNode()
                .boundsInRoot
                .copy(
                    bottom = context.fetchSemanticsNode().boundsInRoot.bottom,
                )
        val before = textBounds()
        val row = ui.onNodeWithContentDescription(rowDescription).fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Title and context must stay vertically centered",
            row.center.y,
            before.center.y,
            1f,
        )
        val icon =
            ui.onNodeWithTag("Activity program ${task.key}", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(
            "Program icon must match the two-line text height",
            before.height,
            icon.height,
            1f,
        )
        assertEquals(
            "Program icon and text must share their center",
            before.center.y,
            icon.center.y,
            1f,
        )
        ui.onNodeWithContentDescription(rowDescription)
            .assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                    appString(R.string.filter_finished),
                ),
            )
        ui.onNodeWithText(appString(R.string.a11y_state_seen)).assertDoesNotExist()
        capture("activity-finished-alignment-dark.png")
        ui.onNodeWithContentDescription(appString(R.string.a11y_mark_seen, "Review")).performClick()
        ui.onNodeWithText(appString(R.string.a11y_state_seen)).assertDoesNotExist()
        ui.onNodeWithText(appString(R.string.mark_seen)).assertIsNotEnabled()
        ui.onNodeWithContentDescription(rowDescription)
            .assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                    appString(R.string.filter_finished) +
                        " · " +
                        appString(R.string.a11y_state_seen),
                ),
            )
        assertEquals("Acknowledging must not move the title block", before, textBounds())
        ui.runOnIdle { assertEquals(0, opened) }
    }

    @Test
    fun workingAndNeedsYouBreatheUniformlyAndFinishedStaysSolid() {
        ui.mainClock.autoAdvance = false
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                androidx.compose.foundation.layout.Row {
                    ActivityStateBar(
                        ActivityState.WORKING,
                        false,
                        androidx.compose.ui.Modifier.testTag("working").size(3.dp, 39.dp),
                    )
                    ActivityStateBar(
                        ActivityState.BLOCKED,
                        false,
                        androidx.compose.ui.Modifier.testTag("attention").size(3.dp, 39.dp),
                    )
                    ActivityStateBar(
                        ActivityState.DONE,
                        false,
                        androidx.compose.ui.Modifier.testTag("done").size(3.dp, 39.dp),
                    )
                }
            }
        }
        ui.mainClock.advanceTimeBy(32)
        ui.waitForIdle()
        val moving = ui.onNodeWithTag("working")
        val attention = ui.onNodeWithTag("attention")
        val solid = ui.onNodeWithTag("done")
        val before = moving.captureToImage().asAndroidBitmap()
        val beforeAttention = attention.captureToImage().asAndroidBitmap()
        val finished = solid.captureToImage().asAndroidBitmap()
        val bounds = moving.fetchSemanticsNode().boundsInRoot
        ui.mainClock.advanceTimeBy(350)
        ui.waitForIdle()
        val after = moving.captureToImage().asAndroidBitmap()
        val afterAttention = attention.captureToImage().asAndroidBitmap()
        assertFalse("Working must breathe", before.sameAs(after))
        assertEquals(
            "Working must breathe uniformly",
            after.getPixel(after.width / 2, after.height / 4),
            after.getPixel(after.width / 2, after.height * 3 / 4),
        )
        for (frame in listOf(before, after)) for (y in
            listOf(frame.height / 4, frame.height * 3 / 4)) {
            val pixel = frame.getPixel(frame.width / 2, y)
            assertTrue(
                "Working rail must stay filled blue",
                android.graphics.Color.blue(pixel) > android.graphics.Color.red(pixel),
            )
        }
        assertFalse("Needs you must pulse", beforeAttention.sameAs(afterAttention))
        for (frame in listOf(beforeAttention, afterAttention)) {
            assertEquals(
                "Attention pulse must brighten uniformly",
                frame.getPixel(frame.width / 2, frame.height / 4),
                frame.getPixel(frame.width / 2, frame.height * 3 / 4),
            )
        }
        assertTrue(
            "Finished bar must remain steady",
            finished.sameAs(solid.captureToImage().asAndroidBitmap()),
        )
        assertEquals(bounds, moving.fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun tabMarkersFillFromTheStartAndBottom() {
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                val color = MaterialTheme.colorScheme.primary
                androidx.compose.foundation.layout.Row {
                    androidx.compose.foundation.Canvas(
                        androidx.compose.ui.Modifier.testTag("horizontal marker").size(39.dp, 3.dp),
                    ) {
                        drawTabMarker(
                            color,
                            androidx.compose.ui.geometry.Offset.Zero,
                            size,
                            percent = 35,
                            brightness = 1f,
                        )
                    }
                    androidx.compose.foundation.Canvas(
                        androidx.compose.ui.Modifier.testTag("vertical marker").size(3.dp, 39.dp),
                    ) {
                        drawTabMarker(
                            color,
                            androidx.compose.ui.geometry.Offset.Zero,
                            size,
                            percent = 35,
                            brightness = 1f,
                            vertical = true,
                        )
                    }
                }
            }
        }
        val horizontal = ui.onNodeWithTag("horizontal marker").captureToImage().asAndroidBitmap()
        val vertical = ui.onNodeWithTag("vertical marker").captureToImage().asAndroidBitmap()
        val filled = horizontal.getPixel(horizontal.width / 5, horizontal.height / 2)
        val track = horizontal.getPixel(horizontal.width * 3 / 5, horizontal.height / 2)
        assertNotEquals(filled, track)
        assertEquals(filled, vertical.getPixel(vertical.width / 2, vertical.height * 4 / 5))
        assertEquals(track, vertical.getPixel(vertical.width / 2, vertical.height * 2 / 5))
    }

    @Test
    fun backgroundMetadataRefreshDoesNotDrawASecondIndicatorOrMoveRows() {
        val server = ServerProfile.create("Mac", "https://fixture.example", "mac")
        val loading = androidx.compose.runtime.mutableStateOf(false)
        val items =
            androidx.compose.runtime.mutableStateOf(listOf(record("done", "Review", "s", "p")))
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                ActivitySheet(
                    listOf(ActivitySource(server, true, loading.value, "", false, items.value)),
                    {},
                    {},
                    {},
                    initialFilter = ActivityFilter.ALL,
                )
            }
        }
        val before = ui.onNodeWithText("Review").fetchSemanticsNode().boundsInRoot
        ui.runOnIdle { loading.value = true }
        ui.onAllNodes(
                SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
                ),
            )
            .assertCountEquals(0)
        assertEquals(before, ui.onNodeWithText("Review").fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle { items.value = emptyList() }
        ui.onNodeWithText(appString(R.string.activity_checking)).assertIsDisplayed()
    }

    @Test
    fun activitySheetDefaultsToAttentionFilter() {
        val server = ServerProfile.create("Mac", "https://fixture.example", "mac")
        val blocked = record("blocked", "Approval needed", "s1", "p1")
        val done = record("done", "Finished build", "s2", "p2")
        ui.setContent {
            RexTheme(true, ThemeCatalog.defaults[0]) {
                ActivitySheet(
                    listOf(ActivitySource(server, true, false, "", false, listOf(blocked, done))),
                    {},
                    {},
                    {},
                )
            }
        }
        ui.onNodeWithText("Approval needed").assertIsDisplayed()
        ui.onNodeWithText("Finished build").assertDoesNotExist()
        ui.onNodeWithText(appString(R.string.filter_all)).performClick()
        ui.onNodeWithText("Approval needed").assertIsDisplayed()
        ui.onNodeWithText("Finished build").assertIsDisplayed()
    }

    @Test
    fun eyeHasAnAccessibleAttentionIndicator() {
        val dark = androidx.compose.runtime.mutableStateOf(false)
        val count = androidx.compose.runtime.mutableIntStateOf(2)
        ui.setContent {
            RexTheme(dark.value, ThemeCatalog.defaults[if (dark.value) 0 else 1]) {
                TerminalActions(true, true, {}, {}, attentionCount = count.intValue)
            }
        }
        val eye =
            ui.onNodeWithContentDescription(appString(R.string.activity))
                .fetchSemanticsNode()
                .boundsInRoot
        val dot =
            ui.onNodeWithTag("Activity unread dot", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        // The reference badges the 19dp eye, not the corner of the 41dp button.
        assertTrue(dot.center.x > eye.center.x && dot.center.x < eye.center.x + eye.width * .25f)
        assertTrue(dot.center.y < eye.center.y && dot.center.y > eye.center.y - eye.height * .2f)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        fun eyeCapture(name: String) {
            File(app.cacheDir, name).outputStream().use {
                ui.onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        eyeCapture("activity-eye-reference-light.png")
        ui.runOnIdle { dark.value = true }
        ui.waitForIdle()
        eyeCapture("activity-eye-reference-dark.png")
        ui.onNodeWithContentDescription(appString(R.string.activity))
            .assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                    appQuantity(R.plurals.a11y_unread_items, 2),
                ),
            )
        ui.runOnIdle { count.intValue = 0 }
        ui.onNodeWithTag("Activity unread dot", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(
            eye,
            ui.onNodeWithContentDescription(appString(R.string.activity))
                .fetchSemanticsNode()
                .boundsInRoot,
        )
    }
}
