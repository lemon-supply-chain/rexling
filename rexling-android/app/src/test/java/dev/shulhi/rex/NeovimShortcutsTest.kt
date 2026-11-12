package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class NeovimShortcutsTest {
    private val nvim=ProgramIdentity.resolve(listOf("nvim"))
    private val state=AppState(phase="connected",selectedSession="s",selectedBlock="p",controlled=true,
        programs=mapOf("p" to nvim),screen=TerminalScreen(80,24,0,0,-1,0,emptyList()))
    private fun input(item: NeovimShortcut,state: AppState=this.state,target: String="same",active: Boolean=true,pending: Boolean=false)=
        NeovimShortcuts.input(state,item,target,"same",active,pending)
    @Test fun defaultsExecuteExCommandsWithExactlyOneEnter() {
        assertTrue(NeovimShortcuts.defaults.any {it.keys==":q"})
        for(item in NeovimShortcuts.defaults) {
            val result=input(item)!!
            assertEquals("\u001c\u000e${item.keys}\r",result)
            assertEquals(1,result.count {it=='\r'});assertFalse(result.contains('\n'))
        }
    }
    @Test fun leaderMacrosSendRealKeysWithoutAnExtraEnter() {
        val item=NeovimShortcuts.parse("LazyGit = <Space>lg").single()
        assertEquals("LazyGit",item.label)
        val result=input(item)!!
        assertEquals("\u001c\u000e lg",result)
    }
    @Test fun supportsExplicitEnterEscapeControlsAndNavigation() {
        assertEquals("\u001b:w\r",NeovimShortcuts.decodeKeys("<Esc>:w<CR>"))
        assertEquals("\u0017\u001d\u0003",NeovimShortcuts.decodeKeys("<C-w><C-]><C-c>"))
        assertEquals("\t\u001b[A\u001bOP",NeovimShortcuts.decodeKeys("<Tab><Up><F1>"))
        assertEquals("<|\\",NeovimShortcuts.decodeKeys("<lt><Bar><Bslash>"))
        assertEquals("\u001bx",NeovimShortcuts.decodeKeys("<A-x>"))
        val result=input(NeovimShortcut("Save",":w<CR>"))!!
        assertTrue(result.endsWith("\r"));assertEquals(1,result.count {it=='\r'})
        assertEquals("\u001c\u000e\u001b:w\r",input(NeovimShortcut("Save","<Esc>:w")))
        assertEquals("\u001c\u000e:w\rgg",input(NeovimShortcut("Save and top",":w<CR>gg")))
    }
    @Test fun roundTripPreservesNamedSequencesAndAnEmptyListCanHideTheButton() {
        val items=NeovimShortcuts.parse(" :w \nLazyGit = <Space>lg\nSave all = :wa<CR>\n")
        assertEquals(items,NeovimShortcuts.decode(NeovimShortcuts.encode(items)))
        assertEquals(items,NeovimShortcuts.parse(NeovimShortcuts.editorText(items)))
        assertTrue(NeovimShortcuts.decode("[]").isEmpty())
        assertTrue(NeovimShortcuts.parse("\n  \n").isEmpty())
        assertEquals(NeovimShortcuts.defaults,NeovimShortcuts.decode(null))
        assertEquals(NeovimShortcuts.defaults,NeovimShortcuts.decode("broken"))
        assertEquals(NeovimShortcuts.defaults,NeovimShortcuts.decode("""[{"label":"Bad","keys":"<Leader>lg"}]"""))
    }
    @Test fun rejectsAmbiguousNotationHiddenControlsDuplicatesAndOversizedKeys() {
        for(text in listOf("Bad = <Leader>lg","Bad = <Bogus>","Bad = x\u001b",":w\n:w","Long = "+"x".repeat(257))) {
            assertTrue(text,runCatching {NeovimShortcuts.parse(text)}.isFailure)
        }
    }
    @Test fun stalePaneDisconnectedReadonlyAndNonNeovimTargetsNeverReceiveKeys() {
        val item=NeovimShortcut(":q",":q")
        assertNull(input(item,target="old-pane"));assertNull(input(item,active=false));assertNull(input(item,pending=true))
        for(other in listOf(state.copy(controlled=false),state.copy(creating=true),state.copy(phase="reconnecting"),
            state.copy(screen=null),state.copy(selectedBlock="new"),state.copy(cachedPrograms=setOf("p")),
            state.copy(programs=mapOf("p" to ProgramIdentity.resolve(listOf("fish")))),
            state.copy(programs=mapOf("p" to ProgramIdentity.resolve(listOf("lazygit")))),state.copy(programs=emptyMap()))) {
            assertNull(input(item,other))
        }
        assertNull(input(NeovimShortcut("Bad","<Leader>lg")))
    }
    @Test fun optionalGroupsPreserveRootOrderAndOnlyContainActions() {
        val text=":w\n[Git]\nLazyGit = <Space>lg\n[Files]\nSave = :wa\n[]\n:q"
        val items=NeovimShortcuts.parse(text)
        assertEquals(listOf(":w","Git","Files",":q"),NeovimShortcuts.entries(items).map {it.label})
        val git=NeovimShortcuts.entries(items,"Git")
        assertEquals(listOf("LazyGit"),git.map {it.label})
        assertTrue(git.all {it is NeovimShortcutEntry.Action})
        assertEquals(items,NeovimShortcuts.decode(NeovimShortcuts.encode(items)))
        assertEquals(items,NeovimShortcuts.parse(NeovimShortcuts.editorText(items)))
        assertEquals("\u001c\u000e lg",input(items[1]))
    }
    @Test fun groupsAreSingleLevelAndLabelsCanRepeatAcrossGroups() {
        val items=NeovimShortcuts.parse((1..32).joinToString("\n") {"[Group $it]\nSave = :w"})
        assertEquals(32,items.size);assertEquals(32,NeovimShortcuts.entries(items).size)
        for(text in listOf("[[Nested]]\n:w","[Empty]","Git = :w\n[Git]\n:q","[Git]\nSave = :w\nSave = :wa"))
            assertTrue(text,runCatching {NeovimShortcuts.parse(text)}.isFailure)
    }
    @Test fun largeFlatAndGroupedListsRoundTripWithoutACountOrSerializedSizeCap() {
        val flat=NeovimShortcuts.parse((1..200).joinToString("\n") {"Action $it = :echo "+"x".repeat(90)})
        assertEquals(200,flat.size)
        val encoded=NeovimShortcuts.encode(flat)
        assertTrue(encoded.length>12000)
        assertEquals(flat,NeovimShortcuts.decode(encoded))
        assertEquals(flat,NeovimShortcuts.parse(NeovimShortcuts.editorText(flat)))
        val grouped=NeovimShortcuts.parse("[Commands]\n"+NeovimShortcuts.editorText(flat))
        assertEquals(200,NeovimShortcuts.entries(grouped,"Commands").size)
        assertEquals(grouped,NeovimShortcuts.decode(NeovimShortcuts.encode(grouped)))
        assertEquals("\u001c\u000e${flat.last().keys}\r",input(grouped.last()))
    }
    @Test fun groupColoursParseRoundTripAndApplyToEveryItemInTheGroup() {
        val items=NeovimShortcuts.parse(":w\n[Git:green]\nLazyGit = <Space>lg\nBlame = <Space>gb\n[]\n[Files:Purple]\nFind = <Space>ff\n[Git]\nLog = <Space>gl")
        assertEquals(listOf(null,WheelCategory.GREEN,WheelCategory.GREEN,WheelCategory.PURPLE,WheelCategory.GREEN),items.map {it.category})
        assertEquals(listOf("","Git","Git","Files","Git"),items.map {it.group})
        val root=NeovimShortcuts.entries(items)
        assertEquals(NeovimShortcutEntry.Group("Git",WheelCategory.GREEN),root[1])
        assertEquals(NeovimShortcutEntry.Group("Files",WheelCategory.PURPLE),root[2])
        assertTrue(NeovimShortcuts.entries(items,"Git").all {it.category==WheelCategory.GREEN})
        assertEquals(items,NeovimShortcuts.parse(NeovimShortcuts.editorText(items)))
        assertEquals(items,NeovimShortcuts.decode(NeovimShortcuts.encode(items)))
        assertTrue(NeovimShortcuts.editorText(items).contains("[Git:green]"))
    }
    @Test fun unknownColourSuffixesStayInTheGroupNameAndConflictsAreRejected() {
        val items=NeovimShortcuts.parse("[Notes:todo]\nOpen = :e notes")
        assertEquals("Notes:todo",items.single().group);assertNull(items.single().category)
        try {NeovimShortcuts.parse("[Git:green]\nA = a\n[Git:blue]\nB = b");fail("Two colours accepted")} catch(_: IllegalArgumentException) {}
        try {NeovimShortcuts.parse("[:green]\nA = a");fail("Colour without a group accepted")} catch(_: IllegalArgumentException) {}
    }
    @Test fun savedProfilesKeepGroupColours() {
        val items=NeovimShortcuts.parse("[Git:orange]\nLazyGit = <Space>lg")
        val profile=ShortcutProfiles.build("p","Git","nvim","",ShortcutEnter.TEXT,true,NeovimShortcuts.editorText(items),emptyList())
        val restored=ShortcutProfiles.decode(ShortcutProfiles.encode(listOf(profile))) {emptyList()}
        assertEquals(WheelCategory.ORANGE,restored.single().items.single().category)
    }
    @Test fun olderSavedListsAndLiteralBracketKeySequencesStillLoad() {
        val old="""[{"label":":w","keys":":w"},{"label":"[]","keys":"[]"}]"""
        assertEquals(listOf(NeovimShortcut(":w",":w"),NeovimShortcut("[]","[]")),NeovimShortcuts.decode(old))
        assertEquals(NeovimShortcuts.defaults,NeovimShortcuts.decode(NeovimShortcuts.encode(NeovimShortcuts.defaults)))
    }
}
