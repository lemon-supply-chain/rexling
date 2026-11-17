package dev.lemon.rex

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FramePresentationTest {
    @get:Rule val ui=createComposeRule()
    private fun screen(color: Int)=TerminalScreen(20,4,null,null,-1,color,
        (0 until 4).flatMap {y->(0 until 20).map {x->TerminalCell(x,y,"",-1,color,0,1)}})

    @Test fun aChromeRecompositionCannotReplaceANewerDirectlyPresentedScreen() {
        val preview=screen(0xff30445d.toInt());val latest=screen(0xff4d6858.toInt())
        val direct=AtomicReference(preview);val controlled=mutableStateOf(false)
        lateinit var view: TerminalView
        ui.setContent {MaterialTheme {
            TerminalCarousel(listOf(BlockItem("a","shell")),"a","a",preview,emptyMap(),emptyMap(),controlled.value,10f,
                {},{},{_,_,_,_,_->},{},{},{_,_->},{},{},{view=it},Modifier.size(320.dp,220.dp),
                bindLiveView={_,terminal->terminal.presentFrame(direct.get());true})
        }}
        ui.runOnIdle {direct.set(latest);view.presentFrame(latest);assertSame(latest,view.screen);controlled.value=true}
        ui.runOnIdle {assertSame("Old Compose frame must not overwrite direct presentation",latest,view.screen)}
    }
    @Test fun aPreviewStaysVisibleUntilTheLiveBindingHasACompleteScreen() {
        val preview=screen(0xff30445d.toInt());val latest=screen(0xff4d6858.toInt())
        val ready=mutableStateOf(false);lateinit var view: TerminalView
        ui.setContent {MaterialTheme {
            TerminalCarousel(listOf(BlockItem("a","shell")),"a","a",preview,emptyMap(),emptyMap(),ready.value,10f,
                {},{},{_,_,_,_,_->},{},{},{_,_->},{},{},{view=it},Modifier.size(320.dp,220.dp),
                bindLiveView={_,terminal->if(ready.value){terminal.presentFrame(latest);true}else false})
        }}
        ui.runOnIdle {assertSame(preview,view.screen);ready.value=true}
        ui.runOnIdle {assertSame(latest,view.screen)}
    }
}
