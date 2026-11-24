package dev.lemon.rex

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class TerminalImeLineTest {
    /** A cursor-aware line editor: validates the resulting text and cursor, not just bytes. */
    private class Editor {
        val points = mutableListOf<Int>()
        var cursor = 0

        fun apply(keys: String) {
            var i = 0
            while (i < keys.length) {
                when {
                    keys.startsWith("\u001b[C", i) -> {
                        cursor = (cursor + 1).coerceAtMost(points.size)
                        i += 3
                    }
                    keys.startsWith("\u001b[D", i) -> {
                        cursor = (cursor - 1).coerceAtLeast(0)
                        i += 3
                    }
                    keys[i] == '\u007f' -> {
                        if (cursor > 0) points.removeAt(--cursor)
                        i++
                    }
                    else -> {
                        val point = keys.codePointAt(i)
                        points.add(cursor++, point)
                        i += Character.charCount(point)
                    }
                }
            }
        }

        fun text() = String(points.toIntArray(), 0, points.size)
    }

    @Test
    fun appendsAndCorrectionsSendOnlyTheChangedTail() {
        val model = TerminalImeLine()
        assertEquals("teh", model.edit("teh", 3))
        assertEquals("\u007f\u007fhe ", model.edit("the ", 4))
        assertEquals("", model.edit("the ", 4))
        assertEquals("next", model.edit("the next", 8))
    }

    @Test
    fun editingAnEarlierWordPreservesTheRemoteCursor() {
        val model = TerminalImeLine()
        val remote = Editor()
        for ((text, cursor) in
            listOf("git stauts" to 10, "git status" to 6, "git sXtatus" to 7, "git status" to 6)) {
            remote.apply(model.edit(text, cursor))
            assertEquals(text, remote.text())
            assertEquals(cursor, remote.cursor)
        }
    }

    @Test
    fun unicodeCorrectionsNeverSplitSurrogatePairs() {
        val model = TerminalImeLine()
        val remote = Editor()
        for ((text, cursor) in listOf("a🦀b" to 3, "a🦁b" to 3, "ab" to 1, "🦁ab" to 2)) {
            remote.apply(model.edit(text, cursor))
            assertEquals(text, remote.text())
            assertEquals(text.codePointCount(0, cursor), remote.cursor)
        }
        model.reset()
        assertEquals("🦀\u001b[D", model.edit("🦀", 1))
        assertEquals(0, model.cursor)
    }

    @Test
    fun resetCannotEraseTextFromAnExternalActionOrAnotherPane() {
        val model = TerminalImeLine()
        assertEquals("first", model.edit("first", 5))
        model.reset()
        assertEquals("", model.text)
        assertEquals(0, model.cursor)
        assertEquals("second", model.edit("second", 6))
    }

    @Test
    fun longValidCorrectionsAreNotSilentlyTurnedIntoAppends() {
        val model = TerminalImeLine()
        val remote = Editor()
        remote.apply(model.edit("x".repeat(256), 256))
        remote.apply(model.edit("replacement", 4))
        assertEquals("replacement", remote.text())
        assertEquals(4, remote.cursor)
    }

    @Test
    fun thousandsOfInsertDeleteReplaceAndCursorEditsAgreeWithTheRemoteLine() {
        val model = TerminalImeLine()
        val remote = Editor()
        val wanted = mutableListOf<Int>()
        val random = Random(126)
        val alphabet = "abc é🦀🦁".codePoints().toArray()
        var cursor = 0
        repeat(4000) {
            when (random.nextInt(5)) {
                0 -> if (cursor > 0) wanted.removeAt(--cursor)
                1 -> if (cursor < wanted.size) wanted.removeAt(cursor)
                2 -> cursor = random.nextInt(wanted.size + 1)
                3 ->
                    if (wanted.isNotEmpty())
                        wanted[random.nextInt(wanted.size)] = alphabet.random(random)
                else -> wanted.add(cursor++, alphabet.random(random))
            }
            val text = String(wanted.toIntArray(), 0, wanted.size)
            val position = wanted.take(cursor).sumOf { Character.charCount(it) }
            remote.apply(model.edit(text, position))
            assertEquals("text at edit $it", text, remote.text())
            assertEquals("cursor at edit $it", cursor, remote.cursor)
            assertEquals("unchanged edit $it", "", model.edit(text, position))
        }
    }
}
