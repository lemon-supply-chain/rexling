package dev.shulhi.rex

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramArtworkTest {
    private val index = ProgramArtworkIndex(
        icons = setOf(
            "claude_code","claude","node","bun","pnpm","htop","top","btop","fish","zsh","shell",
            "nushell","clang","terraform","yazi","terminal"
        ),
        glyphs = setOf(
            "claude","node","bun","pnpm","htop","top","btop","clang","terraform","yazi",
            "terminal","ssh","tmux","nushell"
        ),
        skins = mapOf(
            "TERMINAL" to ProgramSkin(null, "terminal", emptyMap(), emptyList(), true),
            "CLAUDE" to ProgramSkin("claude_code", "claude", emptyMap(), emptyList(), false),
            "NODE" to ProgramSkin("node", "node", mapOf("bun" to "bun", "pnpm" to "pnpm"), emptyList(), false),
            "MONITOR" to ProgramSkin("htop", "htop", mapOf("btop" to "btop", "top" to "top"), emptyList(), false),
            "SSH" to ProgramSkin(null, "ssh", emptyMap(), emptyList(), true),
            "TMUX" to ProgramSkin(null, "tmux", emptyMap(), emptyList(), false),
        ),
        aliases = mapOf("gcc" to "clang", "tofu" to "terraform"),
    )

    private fun artwork(vararg names: String) =
        ProgramArtworkResolver.resolve(ProgramIdentity.resolve(names.toList()), index)

    @Test fun kindSkinWinsBeforeExecutableArtwork() {
        assertEquals(ProgramArtwork("claude_code", "claude", ProgramArtwork.Source.SKIN_DEFAULT), artwork("claude"))
        assertEquals(ProgramArtwork("claude_code", "claude", ProgramArtwork.Source.SKIN_DEFAULT), artwork("claude-code"))
        assertEquals(ProgramArtwork("bun", "bun", ProgramArtwork.Source.SKIN_VARIANT), artwork("bun", "node"))
        assertEquals(ProgramArtwork("pnpm", "pnpm", ProgramArtwork.Source.SKIN_VARIANT), artwork("pnpm"))
        assertEquals(ProgramArtwork("top", "top", ProgramArtwork.Source.SKIN_VARIANT), artwork("top"))
        assertEquals(ProgramArtwork("htop", "htop", ProgramArtwork.Source.SKIN_DEFAULT), artwork("htop"))
    }

    @Test fun executableAliasesAndAncestorsResolveWithoutIdentityKnowingTheCatalog() {
        assertEquals(ProgramArtwork("clang", "clang", ProgramArtwork.Source.EXECUTABLE), artwork("gcc"))
        assertEquals(ProgramArtwork("terraform", "terraform", ProgramArtwork.Source.EXECUTABLE), artwork("tofu"))
        assertEquals(ProgramArtwork("clang", "clang", ProgramArtwork.Source.CANDIDATE), artwork("custom-task", "gcc"))
        assertEquals(ProgramArtwork(null, "ssh", ProgramArtwork.Source.EXECUTABLE), artwork("ssh"))
        assertEquals(ProgramArtwork(null, "tmux", ProgramArtwork.Source.EXECUTABLE), artwork("tmux"))
    }

    @Test fun shellsAndUnmappedProgramsKeepAVisibleFallback() {
        assertEquals(ProgramArtwork("fish", null, ProgramArtwork.Source.EXECUTABLE), artwork("fish"))
        assertEquals(ProgramArtwork("nushell", "nushell", ProgramArtwork.Source.SHELL), artwork("nu"))
        assertEquals(ProgramArtwork("shell", "terminal", ProgramArtwork.Source.SHELL), artwork("sh"))
        assertEquals(ProgramArtwork(null, "terminal", ProgramArtwork.Source.GENERATED), artwork("my-tool"))
    }
}
