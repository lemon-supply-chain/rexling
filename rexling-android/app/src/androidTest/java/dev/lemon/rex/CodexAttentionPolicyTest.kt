package dev.lemon.rex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodexAttentionPolicyTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val endpoint="https://fixture.example"
    private fun task(signal: Boolean=true)=ActivityRecord.parse(JSONObject().put("session","s").put("block","b").put("state","working")
        .put("attentionKey","key").put("attentionVersion","raw-v1").put("app","codex").put("codexTitleEligible",true).put("codexTitleAttention",signal))!!
    @Test fun disabledByDefaultAndOnlyGuardedWorkingReportsBecomeNeedsYou() {
        val name="codex-default-${System.nanoTime()}";val prefs=context.getSharedPreferences(name,0)
        try {
            assertEquals(ActivityState.WORKING,CodexAttentionPolicy.apply(prefs,endpoint,listOf(task()),true).single().state)
            prefs.edit().putBoolean(CodexAttentionPolicy.ENABLED,true).commit()
            val records=listOf(task(),task(false),task().copy(codexTitleEligible=false),task().copy(state=ActivityState.DONE),task().copy(state=ActivityState.ERROR))
            assertEquals(listOf(ActivityState.BLOCKED,ActivityState.WORKING,ActivityState.WORKING,ActivityState.DONE,ActivityState.ERROR),CodexAttentionPolicy.apply(prefs,endpoint,records,true).map {it.state})
            prefs.edit().putBoolean(CodexAttentionPolicy.ENABLED,false).commit()
            assertEquals(ActivityState.WORKING,CodexAttentionPolicy.apply(prefs,endpoint,listOf(task()),true).single().state)
        } finally {context.deleteSharedPreferences(name)}
    }
    @Test fun repeatedRequestsGetNewVersionsButAnimationReconnectAndUiServiceOverlapDoNot() {
        val name="codex-episodes-${System.nanoTime()}";val prefs=context.getSharedPreferences(name,0)
        prefs.edit().putBoolean(CodexAttentionPolicy.ENABLED,true).commit()
        fun observe(signal: Boolean=true,authority: Boolean=true)=CodexAttentionPolicy.apply(prefs,endpoint,listOf(task(signal)),authority).single()
        try {
            val predicted=observe(authority=false)
            val first=observe();assertEquals(predicted.attentionVersion,first.attentionVersion)
            assertEquals(first.attentionVersion,observe().attentionVersion)
            assertEquals(first.attentionVersion,observe(authority=false).attentionVersion)
            val reopened=context.getSharedPreferences(name,0)
            assertEquals(first.attentionVersion,CodexAttentionPolicy.apply(reopened,endpoint,listOf(task()),true).single().attentionVersion)
            assertEquals(ActivityState.WORKING,observe(false).state)
            val nextPredicted=observe(authority=false);val second=observe()
            assertNotEquals(first.attentionVersion,second.attentionVersion)
            assertEquals(nextPredicted.attentionVersion,second.attentionVersion)
            val ledger=AttentionLedger();val server=ServerProfile.create("Fixture",endpoint,"fixture")
            assertEquals(1,ledger.observe(server,listOf(first),true) {false}.size)
            assertTrue(ledger.observe(server,listOf(first.copy(title="Animated")),true) {false}.isEmpty())
            ledger.observe(server,listOf(task(false)),true) {false}
            assertEquals(1,ledger.observe(server,listOf(second),true) {it.attentionVersion==first.attentionVersion}.size)
        } finally {context.deleteSharedPreferences(name)}
    }
}
