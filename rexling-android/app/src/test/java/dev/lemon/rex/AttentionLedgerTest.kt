package dev.lemon.rex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AttentionLedgerTest {
    private val server=ServerProfile.create("Mac","https://mac.example","mac")
    private fun record(state: String="blocked",version: String="v1",title: String="Task")=ActivityRecord.parse(JSONObject()
        .put("session","s").put("block","b").put("id","agent").put("state",state).put("title",title)
        .put("attentionKey","report").put("attentionVersion",version))!!
    @Test fun alertsOnlyInBackgroundAndConsumesForegroundVersions() {
        val ledger=AttentionLedger()
        assertTrue(ledger.observe(server,listOf(record()),false) {false}.isEmpty())
        assertTrue(ledger.observe(server,listOf(record()),true) {false}.isEmpty())
        assertEquals(1,ledger.observe(server,listOf(record(version="v2")),true) {false}.size)
        assertTrue(ledger.observe(server,listOf(record(version="v2")),true) {false}.isEmpty())
    }
    @Test fun titleAnimationAndReconnectDoNotRepeatButNewRequestDoes() {
        val ledger=AttentionLedger()
        assertEquals(1,ledger.observe(server,listOf(record()),true) {false}.size)
        assertTrue(ledger.observe(server,listOf(record(title="·· Animated")),true) {false}.isEmpty())
        val restored=AttentionLedger(ledger.encode())
        assertTrue(restored.observe(server,listOf(record()),true) {false}.isEmpty())
        restored.observe(server,listOf(record(state="working")),true) {false}
        assertEquals(1,restored.observe(server,listOf(record()),true) {false}.size)
    }
    @Test fun seenReportsAndNonAttentionStatesNeverAlert() {
        val ledger=AttentionLedger()
        assertTrue(ledger.observe(server,listOf(record()),true) {true}.isEmpty())
        for(state in listOf("working","idle")) assertTrue(ledger.observe(server,listOf(record(state)),true) {false}.isEmpty())
        assertEquals(1,ledger.observe(server,listOf(record("error")),true) {false}.size)
    }
    @Test fun newCompletionsAlertOnceButHistoricalCompletionsAreBaselined() {
        val ledger=AttentionLedger()
        assertTrue(ledger.observe(server,listOf(record("done")),true) {false}.isEmpty())
        ledger.observe(server,listOf(record("working")),true) {false}
        assertEquals(1,ledger.observe(server,listOf(record("done",version="v2")),true) {false}.size)
        assertTrue(ledger.observe(server,listOf(record("done",version="v2",title="Animated")),true) {false}.isEmpty())
        assertTrue(AttentionLedger(ledger.encode()).observe(server,listOf(record("done",version="v3")),true) {false}.isEmpty())
    }
    @Test fun partialStartupDoesNotReplayOtherOldCompletionsButObservedWorkCanFinish() {
        val ledger=AttentionLedger()
        assertTrue(ledger.observe(server,listOf(record("done")),true,complete=false) {false}.isEmpty())
        val other=record("done").copy(id="other")
        assertTrue(ledger.observe(server,listOf(other,record("working")),true,complete=false) {false}.isEmpty())
        assertEquals(1,ledger.observe(server,listOf(other,record("done",version="v2")),true,complete=false) {false}.size)
        assertTrue(ledger.observe(server,listOf(record("done").copy(id="third")),true) {false}.isEmpty())
        assertEquals(1,ledger.observe(server,listOf(record("done").copy(id="new")),true) {false}.size)
    }
    @Test fun foregroundAndSeenCompletionsAreConsumedWithoutDelayedAlerts() {
        val ledger=AttentionLedger()
        ledger.observe(server,emptyList(),false) {false}
        assertTrue(ledger.observe(server,listOf(record("done")),false) {false}.isEmpty())
        assertTrue(ledger.observe(server,listOf(record("done")),true) {false}.isEmpty())
        assertTrue(ledger.observe(server,listOf(record("done",version="v2")),true) {true}.isEmpty())
        assertTrue(ledger.observe(server,listOf(record("done",version="v2")),true) {false}.isEmpty())
        assertEquals(1,ledger.observe(server,listOf(record("done",version="v3")),true) {false}.size)
    }
    @Test fun migrationFromOldDoneStampsAndPersistedWorkingDoesNotReplayAtStartup() {
        val key=AttentionLedger().key(server,record())
        for(stamp in listOf("state:done","state:working")) {
            val ledger=AttentionLedger(JSONObject().put(key,stamp).toString())
            assertTrue(ledger.observe(server,listOf(record("done")),true) {false}.isEmpty())
            assertTrue(ledger.observe(server,listOf(record("done")),true) {false}.isEmpty())
        }
    }
    @Test fun targetsAreIsolatedByServerAndEndpoint() {
        val ledger=AttentionLedger();val item=record()
        for(profile in listOf(server,server.copy(id="other"),server.copy(endpoint="https://new.example"))) {
            assertEquals(1,ledger.observe(profile,listOf(item),true) {false}.size)
        }
    }
    @Test fun boundedLedgerSurvivesMalformedSavedData() {
        val ledger=AttentionLedger("bad json")
        repeat(2050) {n->ledger.observe(server,listOf(record().copy(id="$n",block="$n")),false) {false}}
        assertEquals(2048,JSONObject(ledger.encode()).length())
    }
}
