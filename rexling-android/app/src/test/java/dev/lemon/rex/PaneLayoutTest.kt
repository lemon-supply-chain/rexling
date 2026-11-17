package dev.lemon.rex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PaneLayoutTest {
    @Test fun keepsWindowCoordinatesAndNestedSplitRatios() {
        val state=AppState.parse("""{"windows":[{"id":"w","blocks":[{"id":"a"},{"id":"b"},{"id":"c"}],"layout":[
            {"id":"a","rect":[0,0,0.35,1]},
            {"id":"b","rect":[0.35,0,0.65,0.4]},
            {"id":"c","rect":[0.35,0.4,0.65,0.6]}]}]}""")
        assertEquals(listOf(PanePlacement("a",0f,0f,.35f,1f),PanePlacement("b",.35f,0f,.65f,.4f),PanePlacement("c",.35f,.4f,.65f,.6f)),state.windows.single().layout)
    }
    @Test fun clipsFloatingPanesToTheWindowAndPreservesLayerOrder() {
        val state=AppState.parse("""{"windows":[{"id":"w","blocks":[{"id":"a"},{"id":"b"}],"layout":[
            {"id":"b","rect":[-0.1,0.2,0.4,0.9],"layer":2,"floating":true},
            {"id":"a","rect":[0,0,1,1],"layer":0}]}]}""")
        val layout=state.windows.single().layout
        assertEquals(listOf("a","b"),layout.map {it.id});assertTrue(layout.last().floating)
        assertEquals(0f,layout.last().x,0f);assertEquals(.3f,layout.last().width,.00001f)
        assertEquals(.8f,layout.last().height,.00001f)
    }
    @Test fun ignoresMalformedOffscreenAndUnknownPanesAndKeepsTheTopmostDuplicate() {
        val state=AppState.parse("""{"windows":[{"id":"w","blocks":[{"id":"a"},{"id":"b"}],"layout":[
            {"id":"a","rect":[0,0,1,1],"layer":0},
            {"id":"a","rect":[0.1,0.1,0.5,0.5],"layer":1,"floating":true},
            {"id":"unknown","rect":[0,0,1,1]}, {"id":"b","rect":[1.2,0,0.1,1]},
            {"id":"b","rect":[0,0,0,1]}, {"id":"b","rect":[0,0,1]},
            {"id":"b","rect":[0,"no",1,1]}, null]}]}""")
        assertEquals(listOf(PanePlacement("a",.1f,.1f,.5f,.5f,1,true)),state.windows.single().layout)
        assertNull(PanePlacement.parse(JSONObject("""{"id":"a","rect":[0,0,"NaN",1]}""")))
    }
    @Test fun olderMetadataKeepsPaneNavigationWithoutInventingALayout() {
        val window=AppState.parse("""{"windows":[{"id":"w","blocks":[{"id":"a"}]}]}""").windows.single()
        assertEquals("a",window.blocks.single().id);assertTrue(window.layout.isEmpty())
    }
}
