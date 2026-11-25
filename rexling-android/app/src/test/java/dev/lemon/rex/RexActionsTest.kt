package dev.lemon.rex

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RexActionsTest {
    @Test
    fun aColonSeparatorNamesACustomAction() {
        val item = NeovimShortcuts.parse("dev := dev_layout").single()
        assertEquals("dev", item.label)
        assertTrue(item.isAction)
        assertEquals("dev_layout", item.keys)
        assertEquals("dev_layout", item.action)
    }

    @Test
    fun plainKeysStayKeysAndAreNotActions() {
        val item = NeovimShortcuts.parse("back = <Esc>").single()
        assertFalse(item.isAction)
        assertNull(item.action)
        // `rex do` is no longer a marker: without `:=` it is typed like any other text.
        val typed = NeovimShortcuts.parse("dev = rex do dev_layout").single()
        assertFalse(typed.isAction)
        assertEquals("rex do dev_layout", typed.keys)
    }

    @Test
    fun nothingAboutTheActionIsValidatedLocally() {
        // Rex owns the action vocabulary, so the name travels as written and Rex reports what it
        // refuses. Extra words travel too, because only a name is forwarded.
        for (name in
            listOf("no_such_action", "dev_layout extra", "com.superlogical.terminal.reset")) {
            val item = NeovimShortcuts.parse("dev := $name").single()
            assertEquals(name, item.action)
        }
        // An action name is never decoded as key notation.
        assertEquals("keys=<C-c>", NeovimShortcuts.parse("dev := keys=<C-c>").single().action)
    }

    @Test
    fun theEditorWritesAndReadsTheActionSeparator() {
        val items =
            NeovimShortcuts.parse(
                """
                [Tabs]
                newtab := dev_layout
                !close := git_diff
                back = <Esc>
                """
                    .trimIndent(),
            )
        assertEquals(listOf("newtab", "close", "back"), items.map { it.label })
        assertEquals(listOf("dev_layout", "git_diff", null), items.map { it.action })
        assertTrue(items[1].confirm)
        assertTrue(items.all { it.group == "Tabs" })
        val text = NeovimShortcuts.editorText(items)
        assertTrue(text.contains("newtab := dev_layout"))
        assertTrue(text.contains("back = <Esc>"))
        assertEquals(items, NeovimShortcuts.parse(text))
    }

    @Test
    fun storedItemsKeepTheActionMarker() {
        val items = NeovimShortcuts.parse("next := dev_layout")
        val stored = JSONArray(NeovimShortcuts.encode(items)).getJSONObject(0)
        assertEquals("dev_layout", stored.getString("keys"))
        assertTrue(stored.getBoolean("rex"))
        assertEquals(items, NeovimShortcuts.decode(NeovimShortcuts.encode(items)))
        // Items stored before actions existed stay key shortcuts.
        assertEquals(
            listOf(NeovimShortcut("ls", "ls -la")),
            NeovimShortcuts.decode("""[{"label":"ls","keys":"ls -la"}]"""),
        )
    }

    @Test
    fun anActionIsNeverSentAsTerminalInput() {
        val state =
            AppState(
                phase = "connected",
                controlled = true,
                selectedSession = "s",
                selectedBlock = "b",
                screen = TerminalScreen(80, 24, null, null, 0, 0, emptyList()),
            )
        val profile =
            ShortcutProfiles.shell(listOf(NeovimShortcut("dev", "dev_layout", rex = true)))
        val item = profile.items.single()
        assertTrue(item.isAction)
        assertNull(ShortcutProfiles.input(state, profile, item, "b", "b", true, false))
        assertNull(NeovimShortcuts.input(state, item, "b", "b", true, false))
        assertEquals(
            "dev_layout",
            ShortcutProfiles.build(
                    "p-test",
                    "Shell",
                    "bash",
                    "",
                    ShortcutEnter.TEXT,
                    true,
                    "dev := dev_layout",
                    emptyList(),
                )
                .items
                .single()
                .action,
        )
    }
}
