package dev.lemon.rex

import androidx.compose.ui.unit.IntSize
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun parsesWideCellsAndColorWithoutDuplicatingTheSpacer() {
        val screen = TerminalScreen.parse(JSONObject("""{"columns":4,"rows":1,"cursor":[3,0],"foreground":16777215,"background":0,"cells":[[0,0,"A",16777215,0,0,1],[1,0,"🦀",16777215,0,1,2],[2,0,"",16777215,0,0,0],[3,0,"",16777215,0,0,1]]}"""))
        assertEquals("A🦀", screen.plainText())
        assertEquals(-1, screen.foreground)
        assertEquals(0xff000000.toInt(), screen.background)
        assertEquals(3, screen.cursorX)
    }
    @Test fun controlAndAltProduceTerminalInput() {
        assertEquals("\u0003", TerminalInput.modified("c", control = true, alt = false))
        assertEquals("\u001bx", TerminalInput.modified("x", control = false, alt = true))
        assertEquals("λ🦀", TerminalInput.modified("λ🦀", control = false, alt = false))
        assertEquals("\u001b[A", TerminalInput.key("up"))
        assertEquals("\r", TerminalInput.key("enter"))
        assertEquals(TerminalInput.SHIFT_ENTER, TerminalInput.key("enter", shift=true))
        assertEquals(TerminalInput.SHIFT_ENTER, TerminalInput.modified("\r", control=false, alt=false, shift=true))
    }
    @Test fun parsesHistoryViewportAndKeepsOldFramesCompatible() {
        val screen=TerminalScreen.parse(JSONObject("""{"columns":2,"rows":2,"foreground":0,"background":0,"cells":[],"scrollbar":[100,25,20]}"""))
        assertEquals(80L,screen.scroll.available)
        assertEquals(55L,screen.scroll.below)
        assertEquals(0L,TerminalScroll().available)
        assertEquals("\u001b[5~",TerminalInput.key("pageup"))
    }
    @Test fun disconnectedStateAllowsMissingOptionalCollections() {
        val state = AppState.parse("""{"revision":1,"phase":"disconnected"}""")
        assertFalse(state.connected)
        assertTrue(state.sessions.isEmpty())
        assertNull(state.screen)
    }
    @Test fun functionKeysUseTheStandardTerminalSequences() {
        val expected=listOf("OP","OQ","OR","OS","[15~","[17~","[18~","[19~","[20~","[21~","[23~","[24~")
        for ((index,sequence) in expected.withIndex()) assertEquals("\u001b$sequence",TerminalInput.key("f${index+1}"))
        assertEquals("\u001b[2~",TerminalInput.key("insert"))
    }

    @Test fun shiftAndCombinedModifiersUseTerminalKeyParameters() {
        assertEquals("\u001b[1;2D",TerminalInput.modified(TerminalInput.key("left"),false,false,true))
        assertEquals("\u001b[1;6C",TerminalInput.key("right",control=true,shift=true))
        assertEquals("\u001b[1;8A",TerminalInput.key("up",control=true,alt=true,shift=true))
        assertEquals("\u001b[Z",TerminalInput.key("tab",shift=true))
        assertEquals("\u001b[15;2~",TerminalInput.key("f5",shift=true))
        assertEquals("\u001b[1;2P",TerminalInput.key("f1",shift=true))
        assertEquals("\u001b[3;6~",TerminalInput.key("delete",control=true,shift=true))
        assertEquals("A!_?",TerminalInput.modified("a1-/",false,false,true))
        assertEquals("\u001b[1;2D",TerminalInput.modified("\u001b[1;2D",false,false,false))
    }
    @Test fun shiftTapIsOneShotWhileHoldSurvivesRepeatedKeysAndCancelClearsIt() {
        val tapped=ShiftKeyState().press().release()
        assertTrue(tapped.active);assertFalse(tapped.consume().active)
        assertFalse(tapped.press().release().active)
        val held=ShiftKeyState().press().consume().consume()
        assertTrue(held.active);assertFalse(held.release().active)
        assertFalse(ShiftKeyState().press().release(cancelled=true).active)
        assertFalse(tapped.press().release(cancelled=true).active)
        assertFalse("A late finger-up after navigation must not relatch Shift",ShiftKeyState().release().active)
    }

    @Test fun controlPopupShortcutsProduceTheExpectedTerminalBytes() {
        val expected=listOf(3,4,26,18,12,1,5,23,10,29)
        assertEquals(expected,controlShortcuts.map {TerminalInput.modified(it,true,false).single().code})
    }

    @Test fun keyboardEnterSubmitsOnlyWhenEnabledAndPastedNewlinesStayLineFeeds() {
        assertEquals("\n",TerminalInput.imeKeys("\n",false))
        assertEquals("\r",TerminalInput.imeKeys("\n",true))
        assertEquals("a\nb",TerminalInput.imeKeys("a\nb",true))
        assertEquals("\n\n",TerminalInput.imeKeys("\n\n",true))
        assertEquals("x",TerminalInput.imeKeys("x",true))
        // Toolbar Shift with the keyboard's Enter is a real Shift+Enter.
        assertEquals(TerminalInput.SHIFT_ENTER,TerminalInput.modified(TerminalInput.imeKeys("\n",true),false,false,true))
    }

    @Test fun uploadsKeepTheFileNameAndFallBackWhenItIsMissing() {
        val png=byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10,0,0,0,0)
        assertEquals("report.pdf",uploadName("report.pdf",ByteArray(0)))
        assertEquals("notes.txt",uploadName("dir/sub/notes.txt",ByteArray(0)))
        assertEquals("ab.zip",uploadName("a\u0000b\n.zip",ByteArray(0)))
        assertEquals("image.png",uploadName(null,png))
        assertEquals("image.png",uploadName("  ",png))
        assertEquals("file",uploadName("..",ByteArray(0)))
        assertEquals("file",uploadName(null,byteArrayOf(1,2,3)))
    }

    @Test fun tailscaleDeviceNamesAreOneLowercaseDnsLabel() {
        assertEquals("",TailscaleHostname.normalize("  "))
        assertEquals("shulhi-s22",TailscaleHostname.normalize(" Shulhi-S22 "))
        assertEquals("a",TailscaleHostname.normalize("a"))
        assertEquals("a".repeat(63),TailscaleHostname.normalize("a".repeat(63)))
        assertEquals(null,TailscaleHostname.normalize("a".repeat(64)))
        assertEquals(null,TailscaleHostname.normalize("-phone"))
        assertEquals(null,TailscaleHostname.normalize("phone-"))
        assertEquals(null,TailscaleHostname.normalize("my phone"))
        assertEquals(null,TailscaleHostname.normalize("phone.local"))
    }
    @Test fun tabBarLayoutRoundTripsAndFallsBackPerScreen() {
        val left=TabBarLayout(TabBarPosition.LEFT,TabRailStyle.TITLES)
        assertEquals(left,TabBarLayout.decode(left.encode(),TabBarLayout()))
        val unfoldedDefault=TabBarLayout(style=TabRailStyle.TITLES)
        assertEquals(unfoldedDefault,TabBarLayout.decode(null,unfoldedDefault))
        assertEquals(TabBarLayout(),TabBarLayout.decode("SIDEWAYS,ICONS",TabBarLayout()))
        assertEquals(TabBarLayout(TabBarPosition.RIGHT,TabRailStyle.ICONS),TabBarLayout.decode("RIGHT,BOGUS",TabBarLayout()))
        assertEquals(false,TabBarLayout().side);assertEquals(true,left.side)
    }

    @Test fun tabWidthClassesFollowTheAppWindow() {
        // Galaxy Z Fold at 420 dpi, and a phone.
        assertEquals(TabWidthClass.NARROW,TabWidthClass.of(350))   // upright inner screen, split half
        assertEquals(TabWidthClass.NARROW,TabWidthClass.of(384))   // phone
        assertEquals(TabWidthClass.MEDIUM,TabWidthClass.of(475))   // cover screen
        assertEquals(TabWidthClass.MEDIUM,TabWidthClass.of(465))   // inner screen sideways, split half
        assertEquals(TabWidthClass.MEDIUM,TabWidthClass.of(704))   // upright inner screen
        assertEquals(TabWidthClass.WIDE,TabWidthClass.of(933))     // inner screen sideways
        assertEquals(TabWidthClass.MEDIUM,TabWidthClass.of(440));assertEquals(TabWidthClass.WIDE,TabWidthClass.of(760))
    }

    @Test fun multiPaneKeepsTheTiledLayoutWhenAFloatingPaneIsFocused() {
        fun tab(vararg rects: PanePlacement)=WindowItem("w","Tab",rects.map {BlockItem(it.id,it.id)},rects.toList())
        val left=PanePlacement("a",0f,0f,.5f,1f);val right=PanePlacement("b",.5f,0f,.5f,1f)
        val floating=PanePlacement("f",.2f,.2f,.3f,.3f,layer=1,floating=true)
        assertEquals(listOf(left,right),multiPaneTiles(tab(left,right,floating),"a"))
        assertEquals(listOf(left,right),multiPaneTiles(tab(left,right,floating),"f",allowFloatingOverlay=true))
        assertEquals(listOf(left),multiPaneTiles(tab(left,floating),"f",allowFloatingOverlay=true))
        assertEquals(listOf(left),multiPaneTiles(tab(left,floating),"a",allowFloatingOverlay=true))
        assertEquals(null,multiPaneTiles(tab(left),"a"))                       // one pane: carousel
        assertEquals(null,multiPaneTiles(tab(left,right,floating),"f"))        // overlay unavailable
        assertEquals(null,multiPaneTiles(tab(left,right,PanePlacement("c",0f,0f,.2f,.2f),PanePlacement("d",0f,0f,.2f,.2f),PanePlacement("e",0f,0f,.2f,.2f)),"a"))
        assertEquals(null,multiPaneTiles(null,"a"))
    }

    @Test fun fullWorkspaceRequiresTheWholeLargeDisplayAndSingleWindowMode() {
        val inner=IntSize(2448,1848)
        val cover=IntSize(900,2120)
        val density=2.5f
        assertTrue(fullWorkspaceWindow(inner,inner,density,sharedWindow=false))
        assertFalse(fullWorkspaceWindow(IntSize(1620,1848),inner,density,sharedWindow=false))
        assertFalse(fullWorkspaceWindow(IntSize(2448,1700),inner,density,sharedWindow=false))
        assertFalse(fullWorkspaceWindow(IntSize(2440,1840),inner,density,sharedWindow=true))
        assertFalse(fullWorkspaceWindow(cover,cover,density,sharedWindow=false))
    }

    @Test fun paneSplitLinesParseOnlyUsableBorders() {
        fun json(text: String)=org.json.JSONObject(text)
        assertEquals(PaneSplitLine("s",true,.5f,0f,0f,1f,1f,"a","b"),
            PaneSplitLine.parse(json("""{"id":"s","direction":"horizontal","ratio":0.5,"rect":[0,0,1,1],"before":"a","after":"b"}""")))
        assertEquals(false,PaneSplitLine.parse(json("""{"id":"s","direction":"vertical","ratio":0.3,"rect":[0.5,0,0.5,1],"before":"a","after":"b"}"""))!!.horizontal)
        assertEquals(null,PaneSplitLine.parse(json("""{"direction":"diagonal","ratio":0.5,"rect":[0,0,1,1],"before":"a","after":"b"}""")))
        assertEquals(null,PaneSplitLine.parse(json("""{"direction":"horizontal","ratio":1.0,"rect":[0,0,1,1],"before":"a","after":"b"}""")))
        assertEquals(null,PaneSplitLine.parse(json("""{"direction":"horizontal","ratio":0.5,"rect":[0,0,1,1],"before":"a","after":"a"}""")))
        assertEquals(null,PaneSplitLine.parse(json("""{"direction":"horizontal","ratio":0.5,"rect":[0,0,0,1],"before":"a","after":"b"}""")))
    }

    @Test fun draggingASplitReflowsEverythingOnEachSideInProportion() {
        // Left pane | right column split into top and bottom, as in a 3-pane tab.
        val a=PanePlacement("a",0f,0f,.5f,1f);val b=PanePlacement("b",.5f,0f,.5f,.5f);val c=PanePlacement("c",.5f,.5f,.5f,.5f)
        val outer=PaneSplitLine("s1",true,.5f,0f,0f,1f,1f,"a","b");val inner=PaneSplitLine("s2",false,.5f,.5f,0f,.5f,1f,"b","c")
        val (panes,lines)=movedSplit(listOf(a,b,c),listOf(outer,inner),outer,.25f)
        fun near(expected: Float,actual: Float)=assertEquals(expected,actual,1e-5f)
        near(0f,panes[0].x);near(.25f,panes[0].width)
        near(.25f,panes[1].x);near(.75f,panes[1].width);near(0f,panes[1].y);near(.5f,panes[1].height)
        near(.25f,panes[2].x);near(.75f,panes[2].width)
        near(.25f,lines[0].ratio)
        near(.25f,lines[1].x);near(.75f,lines[1].width);near(.5f,lines[1].ratio)
        // Moving the inner border leaves the left pane alone.
        val (stacked,_)=movedSplit(listOf(a,b,c),listOf(outer,inner),inner,.8f)
        assertEquals(a,stacked[0]);near(.8f,stacked[1].height);near(.8f,stacked[2].y);near(.2f,stacked[2].height)
    }
}
