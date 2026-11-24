package dev.lemon.rex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramProfileTest {
    @Test
    fun piIsDetectedByNameAndInstallPath() {
        for (candidates in
            listOf(
                listOf("pi"),
                listOf("/home/lemon/.local/bin/pi"),
                listOf(
                    "node",
                    "/home/lemon/.npm-global/lib/node_modules/@earendil-works/pi-coding-agent/dist/cli.js",
                ),
                listOf("node", "/Users/x/.pi/agent/dist/cli.js"),
            )) {
            val profile = ProgramProfile.resolve(ProgramIdentity.resolve(candidates))
            assertEquals("pi must resolve to its profile", PiProfile, profile)
            assertTrue(profile.hiddenCursorInput)
            assertTrue(profile.cursorTapKeyboard)
            assertEquals(ImeAnchor.CONTENT, profile.imeAnchor)
        }
    }

    @Test
    fun cursorAgentIsDetected() {
        for (candidates in listOf(listOf("cursor-agent"), listOf("/usr/local/bin/cursor-agent"))) {
            val profile = ProgramProfile.resolve(ProgramIdentity.resolve(candidates))
            assertEquals(CursorAgentProfile, profile)
            assertTrue(profile.hiddenCursorInput)
            assertTrue(profile.cursorTapKeyboard)
            assertEquals(ImeAnchor.CONTENT, profile.imeAnchor)
        }
    }

    @Test
    fun yaziResizesByNameAndInstallPath() {
        val profile = ProgramProfile.resolve(ProgramIdentity.resolve(listOf("yazi")))
        assertEquals(
            YaziProfile,
            ProgramProfile.resolve(ProgramIdentity.resolve(listOf("/opt/homebrew/bin/yazi"))),
        )
        assertEquals(ImeAnchor.RESIZE, profile.imeAnchor)
        assertFalse(profile.hiddenCursorInput)
        assertFalse(profile.cursorTapKeyboard)
        assertTrue(profile.keyboardSuggestions)
    }

    @Test
    fun hiddenCaretInlineAgentsTrustTheReportedPosition() {
        for (name in listOf("claude", "claude-code")) {
            val profile = ProgramProfile.resolve(ProgramIdentity.resolve(listOf(name)))
            assertEquals(ClaudeProfile, profile)
            assertTrue(profile.hiddenCursorInput)
            assertTrue(profile.cursorTapKeyboard)
            assertEquals(ImeAnchor.CURSOR, profile.imeAnchor)
        }
        val codex = ProgramProfile.resolve(ProgramIdentity.resolve(listOf("codex")))
        assertEquals(CodexProfile, codex)
        assertTrue(codex.hiddenCursorInput)
        assertTrue(codex.cursorTapKeyboard)
        val antigravity = ProgramProfile.resolve(ProgramIdentity.resolve(listOf("antigravity")))
        assertEquals(AntigravityProfile, antigravity)
        assertFalse(antigravity.hiddenCursorInput)
        assertTrue(antigravity.cursorTapKeyboard)
    }

    @Test
    fun editorsExtendEdgesAndDropSuggestions() {
        for (candidates in
            listOf(
                listOf("vim"),
                listOf("nvim"),
                listOf("vi"),
                listOf("gvim"),
                listOf("/usr/bin/vim"),
                listOf("/opt/homebrew/bin/nvim"),
                listOf("helix"),
                listOf("hx"),
                listOf("nano"),
                listOf("micro"),
                listOf("/usr/local/bin/emacs"),
            )) {
            val profile = ProgramProfile.resolve(ProgramIdentity.resolve(candidates))
            assertEquals("editors resolve to one family", EditorProfile, profile)
            assertEquals(TerminalEdgePolicy.EDITOR, profile.edgePolicy)
            assertFalse(profile.keyboardSuggestions)
            assertEquals(ImeAnchor.CURSOR, profile.imeAnchor)
            assertFalse(profile.hiddenCursorInput)
        }
    }

    @Test
    fun everythingElseFallsBackToTheGenericProfile() {
        assertEquals(ProgramProfile.Generic, ProgramProfile.resolve(null))
        assertEquals(ProgramProfile.Generic, ProgramProfile.resolve(ProgramIdentity.Loading))
        assertEquals(
            ProgramProfile.Generic,
            ProgramProfile.resolve(ProgramIdentity.resolve(listOf("zsh"))),
        )
        assertEquals(
            ProgramProfile.Generic,
            ProgramProfile.resolve(ProgramIdentity.resolve(listOf("bash"))),
        )
        for (name in
            listOf(
                "pip",
                "pihole-status",
                "grep",
                "node",
                "python3",
                "git",
                "tmux",
                "btop",
                "fish",
            )) {
            assertEquals(
                "$name must stay generic",
                ProgramProfile.Generic,
                ProgramProfile.resolve(ProgramIdentity.resolve(listOf(name))),
            )
        }
        val generic = ProgramProfile.Generic
        assertFalse(generic.hiddenCursorInput)
        assertFalse(generic.cursorTapKeyboard)
        assertEquals(ImeAnchor.CURSOR, generic.imeAnchor)
        assertEquals(TerminalEdgePolicy.NON_EDITOR, generic.edgePolicy)
        assertTrue(generic.keyboardSuggestions)
    }

    @Test
    fun matchingNormalizesBasenamesAndHonorsPathFragments() {
        val agent =
            ProgramIdentity(
                ProgramKind.TERMINAL,
                "agent",
                "agent",
                candidates = listOf("-agent.exe"),
            )
        assertTrue(matchesCandidates(agent, names = setOf("agent"), pathFragments = emptySet()))
        val agents =
            ProgramIdentity(
                ProgramKind.TERMINAL,
                "agents",
                "agents",
                candidates = listOf("/opt/bin/agents"),
            )
        assertFalse(matchesCandidates(agents, names = setOf("agent"), pathFragments = emptySet()))
        val scoped =
            ProgramIdentity(
                ProgramKind.TERMINAL,
                "agent",
                "agent",
                candidates = listOf("/home/lemon/lib/node_modules/@scope/agent/index.js"),
            )
        assertTrue(
            matchesCandidates(scoped, names = emptySet(), pathFragments = setOf("/@scope/agent/")),
        )
        val windows =
            ProgramIdentity(
                ProgramKind.TERMINAL,
                "agent",
                "agent",
                candidates = listOf("C:\\tools\\agent.cmd"),
            )
        assertTrue(matchesCandidates(windows, names = setOf("agent"), pathFragments = emptySet()))
    }
}
