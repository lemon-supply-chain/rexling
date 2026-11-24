package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class ShortcutProfilesTest {
    private fun program(vararg candidates: String) = ProgramIdentity.resolve(candidates.toList())

    private fun state(program: ProgramIdentity) =
        AppState(
            phase = "connected",
            selectedSession = "s",
            selectedBlock = "p",
            controlled = true,
            programs = mapOf("p" to program),
            screen = TerminalScreen(80, 24, 0, 0, -1, 0, emptyList()),
        )

    private val lazygit =
        ShortcutProfile(
            "lg",
            "lazygit",
            listOf("lazygit"),
            items = NeovimShortcuts.parse("Push = P"),
            enter = ShortcutEnter.NEVER,
            text = "Push = P",
        )
    private val any =
        ShortcutProfile(
            "any",
            "Any",
            listOf("*"),
            items = NeovimShortcuts.parse("Interrupt = <C-c>"),
            text = "Interrupt = <C-c>",
        )
    private val pi =
        ShortcutProfile(
            "pi",
            "pi",
            listOf("*/pi-coding-agent/*"),
            items = NeovimShortcuts.parse("/model"),
            text = "/model",
        )

    @Test
    fun profilesMatchExecutableNamesAndPathPatterns() {
        val antigravity =
            ShortcutProfile(
                "agy",
                "Antigravity",
                listOf("antigravity"),
                items = NeovimShortcuts.parse("Help = ?"),
            )
        assertTrue(
            ShortcutProfiles.matches(ShortcutProfiles.neovim(), program("/opt/homebrew/bin/nvim")),
        )
        assertTrue(ShortcutProfiles.matches(ShortcutProfiles.shell(), program("-fish", "fish")))
        assertFalse(ShortcutProfiles.matches(ShortcutProfiles.shell(), program("nvim")))
        assertFalse(
            ShortcutProfiles.matches(ShortcutProfiles.shell(), program("antigravity", "zsh")),
        )
        assertTrue(ShortcutProfiles.matches(antigravity, program("antigravity", "zsh")))
        assertTrue(
            ShortcutProfiles.matches(
                pi,
                program("/usr/lib/node_modules/@mariozechner/pi-coding-agent/dist/cli.js", "node"),
            ),
        )
        assertFalse(ShortcutProfiles.matches(pi, program("node")))
        assertTrue(ShortcutProfiles.matches(lazygit, program("LazyGit.exe")))
        assertFalse(ShortcutProfiles.matches(ShortcutProfiles.neovim(), ProgramIdentity.Loading))
    }

    @Test
    fun specificProfilesWinOverPatternsAndPatternsOverAnyProgram() {
        val antigravity =
            ShortcutProfile(
                "agy",
                "Antigravity",
                listOf("antigravity"),
                items = NeovimShortcuts.parse("Help = ?"),
            )
        // Shell comes before user-added profiles, but must not steal focus from child programs
        val profiles =
            listOf(
                any,
                pi,
                lazygit,
                ShortcutProfiles.neovim(),
                ShortcutProfiles.shell(),
                antigravity,
            )
        assertEquals("lg", ShortcutProfiles.active(profiles, program("lazygit", "zsh"))?.id)
        assertEquals("agy", ShortcutProfiles.active(profiles, program("antigravity", "zsh"))?.id)
        assertEquals(
            "pi",
            ShortcutProfiles.active(profiles, program("/x/pi-coding-agent/cli.js", "node"))?.id,
        )
        assertEquals("any", ShortcutProfiles.active(profiles, program("htop"))?.id)
        assertEquals(ShortcutProfiles.SHELL, ShortcutProfiles.active(profiles, program("zsh"))?.id)
        assertNull(ShortcutProfiles.active(profiles, null))
        assertNull(ShortcutProfiles.active(profiles, ProgramIdentity.Loading))
    }

    @Test
    fun disabledAndEmptyProfilesAreSkipped() {
        val profiles = listOf(lazygit.copy(enabled = false), any)
        assertEquals("any", ShortcutProfiles.active(profiles, program("lazygit"))?.id)
        assertNull(
            ShortcutProfiles.active(listOf(lazygit.copy(items = emptyList())), program("lazygit")),
        )
    }

    @Test
    fun enterFollowsTheProfileSetting() {
        val shell =
            ShortcutProfiles.shell(
                NeovimShortcuts.parse("gs = git status\nStop = <C-c>\nLine = echo hi<CR>"),
            )
        val s = state(program("fish"))
        fun send(profile: ShortcutProfile, item: NeovimShortcut, at: AppState = s) =
            ShortcutProfiles.input(at, profile, item, "t", "t", true, false)
        assertEquals("git status\r", send(shell, shell.items[0]))
        assertEquals("\u0003", send(shell, shell.items[1]))
        assertEquals("echo hi\r", send(shell, shell.items[2]))
        val g = state(program("lazygit"))
        assertEquals("P", send(lazygit, lazygit.items[0], g))
        val neovim = ShortcutProfiles.neovim()
        assertEquals("\u001c\u000e:w\r", send(neovim, neovim.items[0], state(program("nvim"))))
    }

    @Test
    fun shortcutsOnlyReachTheProgramTheirProfileMatches() {
        val shell = ShortcutProfiles.shell()
        val item = shell.items.first()
        assertNull(
            ShortcutProfiles.input(state(program("nvim")), shell, item, "t", "t", true, false),
        )
        assertNull(
            ShortcutProfiles.input(
                state(program("fish")),
                shell,
                NeovimShortcut("x", "rm -rf ~"),
                "t",
                "t",
                true,
                false,
            ),
        )
        assertNull(
            ShortcutProfiles.input(state(program("fish")), shell, item, "old", "t", true, false),
        )
        assertNull(
            ShortcutProfiles.input(
                state(program("fish")).copy(controlled = false),
                shell,
                item,
                "t",
                "t",
                true,
                false,
            ),
        )
        assertNotNull(
            ShortcutProfiles.input(state(program("fish")), shell, item, "t", "t", true, false),
        )
    }

    @Test
    fun bangMarksShortcutsThatAskFirstAndRoundTrips() {
        val items = NeovimShortcuts.parse("!push = git push\n!git pull\nlit = !echo\n:q")
        assertEquals(listOf(true, true, false, false), items.map { it.confirm })
        assertEquals("git pull", items[1].label)
        assertEquals("git pull", items[1].keys)
        assertEquals("!echo", items[2].keys)
        assertEquals(items, NeovimShortcuts.parse(NeovimShortcuts.editorText(items)))
        assertEquals(items, NeovimShortcuts.decode(NeovimShortcuts.encode(items)))
    }

    @Test
    fun profilesSurviveEncodingAndBadEntriesAreDroppedAlone() {
        val profiles = listOf(ShortcutProfiles.neovim(), ShortcutProfiles.shell(), lazygit, pi)
        assertEquals(
            profiles,
            ShortcutProfiles.decode(ShortcutProfiles.encode(profiles)) { emptyList() },
        )
        val broken = ShortcutProfiles.encode(profiles).replace("\"lazygit\"]", "\"\"]")
        assertEquals(
            listOf(ShortcutProfiles.NEOVIM, ShortcutProfiles.SHELL, "pi"),
            ShortcutProfiles.decode(broken) { emptyList() }.map { it.id },
        )
        assertEquals(
            listOf("fallback"),
            ShortcutProfiles.decode("not json") { listOf(any.copy(id = "fallback")) }.map { it.id },
        )
    }

    @Test
    fun firstLaunchMovesTheEarlierNeovimListIntoItsProfile() {
        val legacy = NeovimShortcuts.parse(":w\nLazyGit = <Space>lg")
        val profiles = ShortcutProfiles.defaults(legacy, false)
        assertEquals(legacy, profiles.first { it.id == ShortcutProfiles.NEOVIM }.items)
        assertFalse(profiles.first { it.id == ShortcutProfiles.NEOVIM }.enabled)
        assertTrue(profiles.first { it.id == ShortcutProfiles.SHELL }.enabled)
    }

    @Test
    fun theSavedTextIsKeptExactlyAsWritten() {
        // The editor shows the saved text again, so a trailing group terminator, blank lines,
        // spacing or an action line are never rewritten on the next edit.
        val text = "[Git:green]\nLazyGit = <Space>lg\n\n[]\n:w \n  dev := dev_layout\n"
        val profile =
            ShortcutProfiles.build(
                "p-text",
                "Shell",
                "bash",
                "",
                ShortcutEnter.TEXT,
                true,
                text,
                emptyList(),
            )
        assertEquals(text, profile.text)
        assertEquals(text, profile.editorText)
        val decoded =
            ShortcutProfiles.decode(ShortcutProfiles.encode(listOf(profile))) { emptyList() }
                .single()
        assertEquals(text, decoded.text)
        assertEquals(text, decoded.editorText)
        assertEquals(profile.items, decoded.items)
    }

    @Test
    fun aProfileStoredBeforeTheTextWasKeptStillOpens() {
        val legacy =
            """[{"id":"p-old","name":"Old","match":["bash"],"prefix":"","enter":"TEXT","enabled":true,"items":[{"label":"ls","keys":"ls -la"}]}]"""
        val decoded = ShortcutProfiles.decode(legacy) { emptyList() }.single()
        assertEquals(listOf(NeovimShortcut("ls", "ls -la")), decoded.items)
        assertEquals("ls = ls -la", decoded.editorText)
        // Saving it once keeps the text it now shows.
        val saved =
            ShortcutProfiles.build(
                decoded.id,
                decoded.name,
                "bash",
                "",
                decoded.enter,
                decoded.enabled,
                decoded.editorText,
                emptyList(),
            )
        assertEquals("ls = ls -la", saved.text)
    }

    @Test
    fun buildRejectsUnclearProfiles() {
        fun error(
            name: String = "Git",
            match: String = "lazygit",
            prefix: String = "",
            text: String = "P",
            others: List<ShortcutProfile> = emptyList(),
        ) = runCatching {
            ShortcutProfiles.build(
                "id",
                name,
                match,
                prefix,
                ShortcutEnter.NEVER,
                true,
                text,
                others,
            )
        }
            .exceptionOrNull()
            ?.message
        assertNull(error())
        assertNotNull(error(name = ""))
        assertNotNull(error(match = " , "))
        assertNotNull(error(prefix = "<Leader>"))
        assertNotNull(error(others = listOf(lazygit.copy(id = "other", name = "git"))))
        assertNull(error(others = listOf(lazygit.copy(id = "id", name = "Git"))))
    }
}
