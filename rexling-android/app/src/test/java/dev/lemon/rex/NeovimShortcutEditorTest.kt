package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class NeovimShortcutEditorTest {
    private val items=listOf(NeovimShortcut(":w",":w"),NeovimShortcut("Git","<Space>lg","Tools"),NeovimShortcut(":q",":q"),NeovimShortcut("Files","<Space>ff","Tools"))
    @Test fun legacyOrderAndKeysSurviveTreeRoundTrip() {
        val tree=NeovimShortcutEditor.fromItems(items)
        val flat=NeovimShortcutEditor.flatten(NeovimShortcutEditor.decode(NeovimShortcutEditor.encode(tree)))
        assertEquals(listOf(items[0],items[1],items[3],items[2]),flat)
        assertEquals(NeovimShortcuts.entries(items),NeovimShortcuts.entries(flat))
        assertEquals(items.filter {it.group=="Tools"},flat.filter {it.group=="Tools"})
    }
    @Test fun movingRenamingAndUngroupingDoNotLoseKeys() {
        val tree=NeovimShortcutEditor.fromItems(items)
        val root=tree.first();val tools=tree.first {it.group=="Tools"}
        val moved=NeovimShortcutEditor.upsert(tree,root.id,null,items[0].copy(group="Tools"))
        assertEquals(4,NeovimShortcutEditor.flatten(moved).size)
        val renamed=moved.map {if(it.id==tools.id) it.copy(group="Work") else it}
        val ungrouped=NeovimShortcutEditor.ungroup(renamed,tools.id)
        assertEquals(items.map {it.keys}.sorted(),NeovimShortcutEditor.flatten(ungrouped).map {it.keys}.sorted())
        assertTrue(NeovimShortcutEditor.flatten(ungrouped).all {it.group.isEmpty()})
    }
    @Test fun emptyGroupsStayInDraftButCannotBeSavedAndNamesMustBeUnique() {
        val empty=NeovimEditNode(group="New",shortcuts=emptyList())
        assertEquals("New",NeovimShortcutEditor.decode(NeovimShortcutEditor.encode(listOf(empty))).single().group)
        assertTrue(runCatching {NeovimShortcutEditor.validate(listOf(empty),populated=true)}.isFailure)
        assertTrue(runCatching {NeovimShortcutEditor.validate(listOf(empty,empty.copy(id="other")))}.isFailure)
        val filled=NeovimShortcutEditor.upsert(listOf(empty),null,null,NeovimShortcut(":w",":w","New"))
        assertEquals(1,NeovimShortcutEditor.validate(filled,populated=true).single().shortcuts.size)
    }
    @Test fun duplicateShortcutNamesCannotOverwriteAnotherShortcut() {
        val tree=NeovimShortcutEditor.fromItems(items)
        val tools=tree.first {it.group=="Tools"}
        assertTrue(runCatching {NeovimShortcutEditor.upsert(tree,null,null,NeovimShortcut("Git",":q","Tools"))}.isFailure)
        val changed=NeovimShortcutEditor.upsert(tree,tools.id,0,NeovimShortcut("Status",":Git","Tools"))
        assertEquals(listOf("Status","Files"),changed.first {it.group=="Tools"}.shortcuts.map {it.label})
    }
    @Test fun recordedTokensPreserveCaseSpecialKeysAndUndoBoundaries() {
        val keys="<Space>lg<C-w><CR>G<lt>"
        val tokens=NeovimKeyRecorder.tokens(keys)
        assertEquals(keys,tokens.joinToString(""))
        assertEquals("<Space>lg<C-w><CR>G",tokens.dropLast(1).joinToString(""))
        assertEquals("<lt>",NeovimKeyRecorder.char('<'))
        assertEquals("Backspace",NeovimKeyRecorder.label("<BS>"))
    }
    @Test fun editorSectionReorderingPreservesTheOtherSectionsMenuAnchors() {
        val first=NeovimEditNode(shortcuts=listOf(NeovimShortcut(":w",":w")))
        val group=NeovimEditNode(group="Git",shortcuts=listOf(NeovimShortcut("Status",":Git","Git")))
        val last=NeovimEditNode(shortcuts=listOf(NeovimShortcut(":q",":q")))
        val reordered=NeovimShortcutEditor.reorderSection(listOf(first,group,last),false,listOf(last.id,first.id))
        assertEquals(listOf(last,group,first),reordered)
        assertEquals("Git",reordered[1].group)
        assertTrue(runCatching {NeovimShortcutEditor.reorderSection(listOf(first,group,last),false,listOf(first.id))}.isFailure)
    }

}
