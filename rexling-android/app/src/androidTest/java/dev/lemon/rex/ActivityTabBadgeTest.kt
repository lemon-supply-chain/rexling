package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ActivityTabBadgeTest {
    @get:Rule val ui=createComposeRule()
    private val dark=mutableStateOf(false)
    private val seen=mutableStateOf(emptySet<String>())
    private val elsewhere=mutableStateOf(false)
    private val selectedTab=mutableStateOf("b")
    private val server=ServerProfile.create("Mac","https://mac.example","mac")
    private val local=ActivityRecord.parse(JSONObject().put("session","s").put("block","a1").put("state","done"))!!
    private val windows=listOf(WindowItem("a","Tab A",listOf(BlockItem("a1","codex"))),WindowItem("b","Tab B",listOf(BlockItem("b1","fish"))))
    private fun show() {
        ui.setContent {RexTheme(dark.value,ThemeCatalog.defaults[if(dark.value) 0 else 1]) {
            val items=if(elsewhere.value) listOf(local,local.copy(session="other")) else listOf(local)
            val source=ActivitySource(server,true,false,"",false,items,seen.value)
            val selected=windows.first {it.id==selectedTab.value}
            val badges=ActivityBadgePolicy.route(listOf(source),server,"s",windows,setOf(selected.blocks.single().id))
            Column(Modifier.width(360.dp).height(80.dp).background(MaterialTheme.colorScheme.background)) {
                TerminalTabs(windows,selected.id,true,{},panePrograms={listOf(ProgramIdentity.resolve(listOf(if(it.id=="a") "codex" else "fish")))},selectedPane={0},
                    tabAttention={badges.tabs[it.id] ?: 0}) {TerminalActions(true,true,{},{},attentionCount=badges.elsewhere)}
            }
        }}
    }
    @Test fun theUnreadDotOnlyShowsWhileTheActivityPaneIsHidden() {
        show()
        val dot=ui.onNodeWithTag("Tab unread dot a",useUnmergedTree=true)
        dot.assertExists()
        ui.runOnIdle {selectedTab.value="a"}
        dot.assertDoesNotExist()
        ui.onNodeWithTag("Activity unread dot",useUnmergedTree=true).assertDoesNotExist()
    }
    @Test fun sameSessionDotIsOnTheIconAndDoesNotChangeTabGeometryInEitherTheme() {
        show()
        val slot=ui.onNodeWithTag("Tab program icon a",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val dot=ui.onNodeWithTag("Tab unread dot a",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue("Badge at top right of icon",dot.center.x>slot.center.x && dot.center.x<slot.right && dot.center.y<slot.center.y)
        ui.onNodeWithTag("Tab unread dot b",useUnmergedTree=true).assertDoesNotExist()
        ui.onNodeWithTag("Activity unread dot",useUnmergedTree=true).assertDoesNotExist()
        fun capture(name: String) {File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,name).outputStream().use {ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}}
        capture("tab-badge-light.png");ui.runOnIdle {dark.value=true};ui.waitForIdle();capture("tab-badge-dark.png")
        assertEquals(slot,ui.onNodeWithTag("Tab program icon a",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle {seen.value=setOf(local.key)}
        ui.onNodeWithTag("Tab unread dot a",useUnmergedTree=true).assertDoesNotExist()
        assertEquals(slot,ui.onNodeWithTag("Tab program icon a",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot)
    }
    @Test fun anotherSessionCanLightTheEyeWhileTheLocalTabRemainsBadged() {
        show();ui.runOnIdle {elsewhere.value=true}
        ui.onNodeWithTag("Activity unread dot",useUnmergedTree=true).assertExists()
        ui.onNodeWithTag("Tab unread dot a",useUnmergedTree=true).assertExists()
        ui.runOnIdle {seen.value=setOf(local.key)}
        ui.onNodeWithTag("Tab unread dot a",useUnmergedTree=true).assertDoesNotExist()
        ui.onNodeWithTag("Activity unread dot",useUnmergedTree=true).assertExists()
    }
}
