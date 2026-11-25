package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class ControlShortcutConfigTest {
    @Test
    fun prefixControlAndFollowingPlainKeysHaveDistinctBytes() {
        assertEquals("\u000c", ControlShortcutConfig.decode("L"))
        assertEquals("\u0000l", ControlShortcutConfig.decode("Space + L"))
        assertEquals("\u0000\u000c", ControlShortcutConfig.decode("Space + Ctrl + L"))
        assertEquals("\u0000l\r", ControlShortcutConfig.decode("Ctrl+Space+L+Enter"))
        assertEquals("\u0000L", ControlShortcutConfig.decode("Space + Shift + L"))
        assertEquals("\u001bl", ControlShortcutConfig.decode("[ + L"))
    }

    @Test
    fun labelsAndSequencesRoundTripAndDefaultsStayCompatible() {
        val items = ControlShortcutConfig.parse("L\nWorkspace = Space + L\nSpace + Ctrl + L")
        assertEquals(listOf("^L", "Workspace", "^Space ^L"), items.map { it.label })
        assertEquals(items, ControlShortcutConfig.parse(ControlShortcutConfig.editorText(items)))
        assertEquals(ControlShortcutConfig.defaults, ControlShortcutConfig.restore(null))
        assertEquals(ControlShortcutConfig.defaults, ControlShortcutConfig.restore("Ctrl +"))
        assertEquals(
            listOf(3, 4, 26, 18, 12, 1, 5, 23, 10, 29),
            ControlShortcutConfig.defaults.map {
                ControlShortcutConfig.decode(it.keys).single().code
            },
        )
        assertEquals("Control C", ControlShortcutConfig.defaults.first().description)
    }

    @Test
    fun invalidSequencesAndDuplicateLabelsAreRejected() {
        for (text in
            listOf(
                "",
                "Ctrl",
                "Space +",
                "Space ++ L",
                "NotAKey",
                "C\nC",
                "Name = C\nName = D",
                "Name = \u001b",
                "😀",
            )) {
            assertTrue(
                "Must reject $text",
                runCatching { ControlShortcutConfig.parse(text) }.isFailure,
            )
        }
    }

    @Test
    fun namedNavigationKeysAndPlainPlusAreSupported() {
        assertEquals("\u0000\u001b[D", ControlShortcutConfig.decode("Space + Left"))
        assertEquals("\u0000+", ControlShortcutConfig.decode("Space + Plus"))
        assertEquals("\u0000\u001b[1;5D", ControlShortcutConfig.decode("Space + Ctrl + Left"))
    }

    @Test
    fun recordedKeysRoundTripAndOptionalNamesValidate() {
        val keys =
            listOf(
                RecordedControlKey("Space"),
                RecordedControlKey("l"),
                RecordedControlKey("Enter", control = true),
            )
        val sequence = ControlKeyRecorder.encode(keys)
        assertEquals(keys, ControlKeyRecorder.decode(sequence))
        assertEquals("Ctrl+Space", keys[0].chip(0))
        assertEquals("L", keys[1].chip(1))
        val item = ControlKeyRecorder.item("Workspace", keys)
        assertEquals("Workspace", item.label)
        assertEquals("\u0000l\r", ControlShortcutConfig.decode(item.keys))
        assertTrue(runCatching { ControlKeyRecorder.item("Empty", emptyList()) }.isFailure)
        assertEquals("Plus", ControlKeyRecorder.token('+'))
    }
}
