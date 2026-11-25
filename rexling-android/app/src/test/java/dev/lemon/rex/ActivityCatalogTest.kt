package dev.lemon.rex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActivityCatalogTest {
    private fun record(state: String, id: String = ""): ActivityRecord =
        ActivityRecord.parse(
            JSONObject()
                .put("session", "s")
                .put("block", "b")
                .put("state", state)
                .put("id", id)
                .put("app", "codex")
                .put("title", "Task")
                .put("message", "Details"),
        )!!

    private val server = ServerProfile.create("Linux", "https://linux.example", "a")

    @Test
    fun filtersAndPriorityPreserveExactPaneTargets() {
        val items =
            listOf(
                record("working"),
                record("done", "done"),
                record("blocked", "worker"),
                record("error", "error"),
            )
        val source = ActivitySource(server, true, false, "", false, items)
        assertEquals(
            listOf("blocked", "error", "working", "done"),
            ActivityCatalog.records(source, ActivityFilter.ALL).map { it.state.wire },
        )
        assertEquals(2, ActivityCatalog.records(source, ActivityFilter.ATTENTION).size)
        assertEquals(
            "done",
            ActivityCatalog.records(source, ActivityFilter.FINISHED).single().state.wire,
        )
        assertEquals("worker", ActivityCatalog.records(source, ActivityFilter.ALL).first().id)
        assertTrue(items.map { it.key }.distinct().size == 4)
    }

    @Test
    fun malformedStateAndUnsafePresentationAreHandled() {
        assertNull(
            ActivityRecord.parse(JSONObject("""{"session":"s","block":"b","state":"future"}""")),
        )
        assertNull(ActivityRecord.parse(JSONObject("""{"state":"working"}""")))
        val parsed =
            ActivityRecord.parse(
                JSONObject(
                    """{"session":"s","block":"b","state":"blocked","kind":"permission","title":"Title\u202e\n","progress":255,"updatedAt":"invalid"}""",
                ),
            )!!
        assertEquals("Title", parsed.title)
        assertEquals(R.string.activity_kind_approval, parsed.stateLabelRes)
        assertNull(parsed.progress)
        assertEquals(0L, parsed.timestamp)
    }

    @Test
    fun activityMetadataParsesAndUnknownRecordsDoNotBecomeIdle() {
        val state =
            AppState.parse(
                """{"activity":[{"session":"s","block":"b","state":"working","progress":42},{"state":"future"}],"activityLoading":true,"activityPartial":true}""",
            )
        assertEquals(1, state.activity.size)
        assertEquals(42, state.activity.single().progress)
        assertTrue(state.activityLoading)
        assertTrue(state.activityPartial)
    }

    @Test
    fun seenAttentionMovesBehindCurrentWork() {
        val blocked = record("blocked")
        val working = record("working", "work")
        val source =
            ActivitySource(
                server,
                true,
                false,
                "",
                false,
                listOf(blocked, working),
                setOf(blocked.key),
            )
        assertEquals(working.key, ActivityCatalog.records(source, ActivityFilter.ALL).first().key)
    }

    @Test
    fun generatedWindowLabelsDoNotLeakIntoDescriptionsOrChangeTargets() {
        val item = record("done").copy(sessionLabel = "Rexling development")
        for (label in
            listOf(
                "Window e4617c1d",
                "window-0123456789abcdef",
                "Window 01234567-89ab-cdef-0123-456789abcdef",
            )) {
            val generated = item.copy(windowLabel = label)
            assertEquals("Rexling development", generated.context)
            assertEquals(item.key, generated.key)
        }
        for (label in listOf("shell", "Build logs", "Window layouts", "Window deadbeef notes")) {
            assertEquals("Rexling development · $label", item.copy(windowLabel = label).context)
        }
    }
}
