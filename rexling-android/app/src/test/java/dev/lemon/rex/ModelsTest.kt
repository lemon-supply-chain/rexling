package dev.lemon.rex

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
}
