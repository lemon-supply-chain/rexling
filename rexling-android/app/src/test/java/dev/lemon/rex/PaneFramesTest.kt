package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class PaneFramesTest {
    private val frame=TerminalScreen(2,1,null,null,-1,0,emptyList())
    @Test fun ordinaryOutputAndMetadataKeepTheSamePreviewMap() {
        val previews=mapOf("other" to frame)
        val old=AppState(selectedBlock="live",screen=frame)
        assertSame(previews,updatePaneFrames(previews,old,old.copy(screen=frame.copy(background=1))))
        assertSame(previews,updatePaneFrames(previews,AppState(),AppState()))
    }
    @Test fun navigationRetainsTheLeavingFrameAfterMergingPreviews() {
        val latest=frame.copy(background=2);val old=AppState(selectedBlock="a",screen=latest)
        val next=AppState(selectedBlock="b",previewFrames=mapOf("a" to frame,"b" to frame))
        val current=mapOf("earlier" to frame);val updated=updatePaneFrames(current,old,next)
        assertSame(latest,updated["a"]);assertSame(frame,updated["b"]);assertSame(frame,updated["earlier"])
        assertEquals(setOf("earlier"),current.keys)
    }
    @Test fun cacheEvictionKeepsTheExistingInsertionOrderAndSixteenFrameLimit() {
        val current=(0..15).associate {"p$it" to frame}
        val updated=updatePaneFrames(current,AppState(selectedBlock="p4",screen=frame),
            AppState(selectedBlock="p17",previewFrames=mapOf("p16" to frame,"p17" to frame)))
        assertEquals((2..17).map {"p$it"},updated.keys.toList());assertEquals(16,current.size)
    }
}
