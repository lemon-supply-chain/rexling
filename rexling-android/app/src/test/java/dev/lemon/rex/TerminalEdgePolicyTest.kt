package dev.lemon.rex

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalEdgePolicyTest {
    @Test fun nullOrPendingDefaultsToNonEditor() {
        assertEquals(TerminalEdgePolicy.NON_EDITOR, TerminalEdgePolicies.policy(null))
        assertEquals(TerminalEdgePolicy.NON_EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.Loading))
    }

    @Test fun shellsAndToolsDefaultToNonEditor() {
        assertEquals(TerminalEdgePolicy.NON_EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("zsh"))))
        assertEquals(TerminalEdgePolicy.NON_EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("bash"))))
        assertEquals(TerminalEdgePolicy.NON_EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("fish"))))
        assertEquals(TerminalEdgePolicy.NON_EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("yazi"))))
        assertEquals(TerminalEdgePolicy.NON_EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("btop"))))
        assertEquals(TerminalEdgePolicy.NON_EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("htop"))))
        assertEquals(TerminalEdgePolicy.NON_EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("git", "log"))))
        assertEquals(TerminalEdgePolicy.NON_EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("python3"))))
    }

    @Test fun vimAndNeovimResolveToEditor() {
        assertEquals(TerminalEdgePolicy.EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("vim"))))
        assertEquals(TerminalEdgePolicy.EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("nvim"))))
        assertEquals(TerminalEdgePolicy.EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("vi"))))
        assertEquals(TerminalEdgePolicy.EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("/usr/bin/vim"))))
        assertEquals(TerminalEdgePolicy.EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("/opt/homebrew/bin/nvim"))))
    }

    @Test fun modernTerminalEditorsResolveToEditor() {
        assertEquals(TerminalEdgePolicy.EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("helix"))))
        assertEquals(TerminalEdgePolicy.EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("hx"))))
        assertEquals(TerminalEdgePolicy.EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("nano"))))
        assertEquals(TerminalEdgePolicy.EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("micro"))))
        assertEquals(TerminalEdgePolicy.EDITOR, TerminalEdgePolicies.policy(ProgramIdentity.resolve(listOf("/usr/local/bin/emacs"))))
    }
}
