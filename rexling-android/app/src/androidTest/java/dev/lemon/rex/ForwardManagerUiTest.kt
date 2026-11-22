package dev.lemon.rex

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ForwardManagerUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun forwardsAcrossServersOfferSeparateStartAndStopControls() {
        val original=PortForwards.items.toList();var started=""
        try {
            ui.runOnIdle {
                PortForwards.items.clear()
                PortForwards.items+=PortForward("a","MacBook","127.0.0.1",3000,"stopped",serverId="mac",endpoint="https://mac.example",session="s1",localPort=3000)
                PortForwards.items+=PortForward("b","Shipyard","127.0.0.1",5173,"active","127.0.0.1:5173",serverId="shipyard",endpoint="https://shipyard.example",session="s2",localPort=5173)
            }
            ui.setContent {MaterialTheme {PortForwardSheet(emptyList(),"",null,{},onStart={started=it}) {_,_,_,_->}}}
            ui.onNodeWithText("MacBook").assertIsDisplayed();ui.onNodeWithText("Shipyard").assertIsDisplayed()
            val port=ui.onNodeWithText("5173",useUnmergedTree=true).getUnclippedBoundsInRoot()
            val url=ui.onNodeWithText("http://127.0.0.1:5173",useUnmergedTree=true).getUnclippedBoundsInRoot()
            val remove=ui.onNodeWithContentDescription("Remove forward to 127.0.0.1:5173").getUnclippedBoundsInRoot()
            val copy=ui.onNodeWithContentDescription("Copy forwarded URL").getUnclippedBoundsInRoot()
            assertEquals("URL aligns with port",port.left.value,url.left.value,.5f)
            assertEquals("Copy aligns under remove",(remove.left.value+remove.right.value)/2,(copy.left.value+copy.right.value)/2,.5f)
            ui.onNodeWithContentDescription("Start").performClick();ui.runOnIdle {assertEquals("a",started)}
            ui.onNodeWithContentDescription("Stop").assertIsDisplayed()
        } finally {ui.runOnIdle {PortForwards.items.clear();PortForwards.items.addAll(original)}}
    }
}
