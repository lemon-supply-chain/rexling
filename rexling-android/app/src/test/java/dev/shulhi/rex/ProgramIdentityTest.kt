package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class ProgramIdentityTest {
    @Test fun cloudflareFollowsExecutableAndEffectiveAppMetadataWithoutGuessingFromTitles() {
        for(candidate in listOf("cloudflared","/opt/homebrew/bin/cloudflared","C:\\tools\\cloudflared.exe","cloudflare")) {
            val program=ProgramIdentity.resolve(listOf(candidate,"fish"))
            assertEquals(ProgramKind.CLOUDFLARE,program.kind)
            assertEquals("Cloudflare",program.label)
            assertEquals("cloudflared",program.title)
        }
        assertEquals(ProgramKind.CLOUDFLARE,ProgramIdentity.resolve(listOf("fish","cloudflared")).kind)
        assertEquals(ProgramKind.TERMINAL,ProgramIdentity.resolve(listOf("my-cloudflared-helper")).kind)
        val state=AppState.parse("""{"programs":{"a":["/opt/homebrew/bin/cloudflared"],"b":["fish"]},"titles":{"b":"cloudflared"}}""")
        assertEquals(ProgramKind.CLOUDFLARE,state.programs["a"]?.kind)
        assertEquals(ProgramKind.TERMINAL,state.programs["b"]?.kind)
    }
    @Test fun rexFollowsExecutableAndEffectiveAppMetadataWithoutGuessingFromTitles() {
        for(candidate in listOf("rex","/opt/rex/bin/rex","C:\\tools\\rex.exe","rex-server","rex-cli","rex-aarch64-unknown-linux-musl")) {
            val program=ProgramIdentity.resolve(listOf(candidate,"fish"))
            assertEquals(ProgramKind.REX,program.kind)
            assertEquals("Rex",program.label)
            assertEquals("rex",program.title)
        }
        assertEquals(ProgramKind.REX,ProgramIdentity.resolve(listOf("fish","rex")).kind)
        assertEquals(ProgramKind.TERMINAL,ProgramIdentity.resolve(listOf("my-rex-helper")).kind)
        val state=AppState.parse("""{"programs":{"a":["/usr/local/bin/rex"],"b":["fish"]},"titles":{"b":"rex"}}""")
        assertEquals(ProgramKind.REX,state.programs["a"]?.kind)
        assertEquals(ProgramKind.TERMINAL,state.programs["b"]?.kind)
    }
    @Test fun recognizesAgentsInsideInterpretersAndNativeInstallations() {
        assertEquals(ProgramKind.CLAUDE,ProgramIdentity.resolve(listOf("claude-code","node")).kind)
        assertEquals(ProgramKind.CLAUDE,ProgramIdentity.resolve(listOf("/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js","node")).kind)
        assertEquals(ProgramKind.CODEX,ProgramIdentity.resolve(listOf("/usr/bin/codex-aarch64-unknown-linux-musl")).kind)
        assertEquals(ProgramKind.CODEX,ProgramIdentity.resolve(listOf("C:\\tools\\codex.exe")).kind)
        assertEquals(ProgramKind.CODEX,ProgramIdentity.resolve(listOf("/usr/lib/node_modules/@openai/codex/bin/codex.js","node")).kind)
        for (candidate in listOf("agy", "antigravity", "antigravity-cli", "/Users/xiaobo/.gemini/antigravity/bin/agy", "/usr/local/bin/antigravity")) {
            val program = ProgramIdentity.resolve(listOf(candidate, "zsh"))
            assertEquals(ProgramKind.ANTIGRAVITY, program.kind)
            assertEquals("Antigravity", program.label)
            assertEquals("antigravity", program.title)
            assertFalse(program.isShell)
        }
        assertTrue(ProgramIdentity.resolve(listOf("zsh")).isShell)
        assertTrue(ProgramIdentity.resolve(listOf("bash")).isShell)
        assertTrue(ProgramIdentity.resolve(listOf("fish")).isShell)
    }
    @Test fun usesExecutableMetadataAndFallsBackWithoutGuessingFromTitles() {
        assertEquals(ProgramKind.NEOVIM,ProgramIdentity.resolve(listOf("/usr/local/bin/nvim")).kind)
        assertEquals(ProgramKind.TERMINAL,ProgramIdentity.resolve(listOf("my-nvim-helper")).kind)
        assertEquals(ProgramKind.TERMINAL,ProgramIdentity.resolve(listOf("/projects/claude/custom-task")).kind)
        assertEquals(ProgramKind.PYTHON,ProgramIdentity.resolve(listOf("/tmp/custom.py","python3.12")).kind)
        assertEquals(ProgramIdentity.Terminal,ProgramIdentity.resolve(emptyList()))
        assertEquals("fish",ProgramIdentity.resolve(listOf("-fish")).label)
    }
    @Test fun headerDirectoryIsOptionalAndStripsInvisibleFormatting() {
        val state=AppState.parse("""{"directories":{"a":"~/code/project","b":"/tmp/\u202ehidden\n"}}""")
        assertEquals("~/code/project",state.directories["a"])
        assertEquals("/tmp/hidden",state.directories["b"])
        assertTrue(AppState.parse("{}").directories.isEmpty())
        assertEquals("Task title",AppState.parse("""{"titles":{"a":"Task \u202etitle\n"}}""").titles["a"])
        assertTrue(AppState.parse("{}").titles.isEmpty())
        assertEquals(setOf("a"),AppState.parse("""{"working":["a"]}""").workingBlocks)
        assertTrue(AppState.parse("{}").workingBlocks.isEmpty())
        assertEquals("claude",ProgramIdentity.resolve(listOf("claude-code")).title)
    }
    @Test fun programMetadataSurvivesAnOrdinaryStateUpdateWithoutInventingPrograms() {
        val state=AppState.parse("""{"programs":{"a":["nvim"],"b":["claude-code","node"]},"windows":[{"id":"w","label":"codex","blocks":[{"id":"a","label":"shell"},{"id":"c","label":"claude"}]}]}""")
        assertEquals(ProgramKind.NEOVIM,state.programs["a"]?.kind)
        assertEquals(ProgramKind.CLAUDE,state.programs["b"]?.kind)
        assertNull(state.programs["c"])
        assertTrue(AppState.parse("{}").programs.isEmpty())
    }
    @Test fun defaultTabsFollowTheirFocusedProgramWhileCustomNamesStayPut() {
        val codex=ProgramIdentity.resolve(listOf("codex"))
        val nvim=ProgramIdentity.resolve(listOf("nvim"))
        for (label in listOf("shell", "Shell", "Terminal", "Terminal 2", "Window 1", "Window 494741a8", "")) {
            assertEquals("codex",codex.tabTitle(label,"shell",1))
            assertEquals("nvim",nvim.tabTitle(label,"shell",1))
        }
        assertEquals("Investigate Rex protocol",codex.tabTitle("shell","shell",1,"Investigate Rex protocol"))
        assertEquals("Investigate Rex protocol",codex.paneTitle("shell","Investigate Rex protocol"))
        assertEquals("deploy",codex.tabTitle("deploy","shell",1,"Investigate Rex protocol"))
        assertEquals("deploy",codex.tabTitle("deploy","shell",1))
        assertEquals("Window management",codex.tabTitle("Window management","shell",1))
        assertEquals("shell",ProgramIdentity.Terminal.tabTitle("shell","shell",1))
        assertEquals("build",ProgramIdentity.Terminal.tabTitle("","build",1))
        assertEquals("Terminal 3",ProgramIdentity.Terminal.tabTitle("","",3))
    }

    @Test fun shellsShowExecutableNamesInsteadOfRepeatingTheirDirectoryTitles() {
        for (executable in listOf("-fish", "/bin/zsh", "/bin/bash", "/usr/bin/dash", "pwsh.exe")) {
            val shell=ProgramIdentity.resolve(listOf(executable))
            assertEquals(shell.title,shell.paneTitle("shell","~/code/project"))
            assertEquals(shell.title,shell.tabTitle("shell","shell",1,"~/code/project"))
            assertEquals("deploy",shell.tabTitle("deploy","shell",1,"~/code/project"))
        }
        assertEquals("fish",ProgramIdentity.resolve(listOf("-fish")).paneTitle("shell","~"))
        assertEquals("Investigate Rex protocol",ProgramIdentity.resolve(listOf("codex")).paneTitle("shell","Investigate Rex protocol"))
        assertEquals("Custom task",ProgramIdentity.resolve(listOf("my-agent")).paneTitle("shell","Custom task"))
    }

    @Test fun generatedIdsNeverBecomeProgramOrTabTitles() {
        val codex=ProgramIdentity.resolve(listOf("codex"))
        val ids=listOf("window-123abc","window:01a190c9-ccc5-767c-90bd-2343aee8b5ad","Tab abc123","block-abc-123","01a190c9-ccc5-767c-90bd-2343aee8b5ad")
        for(id in ids) {
            assertEquals("codex",codex.tabTitle(id,id,1,id))
            assertEquals("Loading…",ProgramIdentity.Loading.tabTitle(id,id,1,id))
            assertEquals("Terminal 1",ProgramIdentity.Terminal.tabTitle(id,id,1,id))
        }
        assertEquals("Window management",codex.tabTitle("Window management","shell",1))
        assertEquals("Task title",ProgramIdentity.Loading.paneTitle("shell","Task title"))
    }

    @Test fun prefersChildCommandOverShellInProcessHierarchy() {
        val docker = ProgramIdentity.resolve(listOf("fish", "docker"))
        assertEquals("docker", docker.label)
        assertFalse(docker.isShell)

        val cargo = ProgramIdentity.resolve(listOf("zsh", "cargo"))
        assertEquals("cargo", cargo.label)
        assertFalse(cargo.isShell)

        val bun = ProgramIdentity.resolve(listOf("bun", "node"))
        assertEquals(ProgramKind.NODE, bun.kind)
        assertEquals("bun", bun.executable)

        val top = ProgramIdentity.resolve(listOf("top"))
        assertEquals(ProgramKind.MONITOR, top.kind)
        assertEquals("top", top.executable)
    }

}
