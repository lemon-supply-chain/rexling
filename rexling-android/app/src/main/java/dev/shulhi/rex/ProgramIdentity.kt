package dev.shulhi.rex

import java.util.Locale

enum class ProgramKind { TERMINAL, CLAUDE, CODEX, NEOVIM, VIM, GIT, PYTHON, NODE, SSH, TMUX, MONITOR, CLOUDFLARE, REX, ANTIGRAVITY }
data class ProgramIdentity(
    val kind: ProgramKind,
    val label: String,
    val executable: String = "",
    val pending: Boolean = false,
) {
    var candidates: List<String> = emptyList()
        internal set
    val title: String get() = when(kind) {
        ProgramKind.CLAUDE -> "claude"; ProgramKind.CODEX -> "codex"; ProgramKind.ANTIGRAVITY -> "antigravity"
        ProgramKind.NEOVIM -> "nvim"; ProgramKind.VIM -> "vim"; ProgramKind.GIT -> "git"; ProgramKind.PYTHON -> "python"
        ProgramKind.NODE -> "node"; ProgramKind.SSH -> "ssh"; ProgramKind.TMUX -> "tmux"
        ProgramKind.MONITOR -> "monitor"; ProgramKind.CLOUDFLARE -> "cloudflared"; ProgramKind.REX -> "rex"; ProgramKind.TERMINAL -> label
    }
    /** Shell OSC titles usually describe cwd; keep the executable name above the directory. */
    val isShell: Boolean get() = kind == ProgramKind.TERMINAL &&
        (label.lowercase(Locale.ROOT).removeSuffix(".exe") in shellNames || executable.lowercase(Locale.ROOT).removeSuffix(".exe") in shellNames)

    /** A task title is presentation only; executable identity still chooses the icon. */
    fun paneTitle(paneLabel: String, reportedTitle: String = ""): String =
        if (isShell) title else reportedTitle.takeUnless(::genericTitle) ?: paneLabel.takeUnless(::genericTitle) ?: title

    /** Explicit tab names stay put; defaults follow the remembered focused pane. */
    fun tabTitle(windowLabel: String, paneLabel: String, number: Int, reportedTitle: String = ""): String {
        if (!genericTitle(windowLabel)) return windowLabel
        val focused=paneTitle(paneLabel,reportedTitle)
        return if (focused != Terminal.title) focused else windowLabel.takeIf { it.isNotBlank() && !defaultTitle.matches(it) } ?: "Terminal $number"
    }

    companion object {
        internal val shellNames = setOf("fish", "zsh", "bash", "sh", "dash", "ash", "ksh", "mksh", "tcsh", "csh", "nu", "nushell", "pwsh", "powershell", "cmd")
        private val defaultTitle = Regex("(?:(?:Window|Tab|Block)[ _:-]+[0-9a-f]+(?:-[0-9a-f]+)*|Terminal \\d+|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})", RegexOption.IGNORE_CASE)
        private val codexRegex = Regex("codex-(aarch64|x86_64)-.*")
        private val pythonRegex = Regex("python[23](\\.\\d+)?")
        private val rexRegex = Regex("rex-(aarch64|x86_64)-.*")
        private fun genericTitle(value: String): Boolean = value.isBlank() ||
            value.lowercase(Locale.ROOT) in setOf("shell", "terminal", "window") || defaultTitle.matches(value)
        val Terminal = ProgramIdentity(ProgramKind.TERMINAL, "Terminal")
        val Loading = ProgramIdentity(ProgramKind.TERMINAL,"Loading…",pending=true)
        fun resolve(candidates: List<String>): ProgramIdentity {
            fun create(identity: ProgramIdentity) = identity.apply { this.candidates = candidates }
            val isBrew = candidates.any { it.replace('\\', '/').lowercase(Locale.ROOT).let { p ->
                p == "brew" || p.endsWith("/brew") || p.contains("/homebrew/library/homebrew/") ||
                    p.substringAfterLast('/') in listOf("homebrew", "hombrew", "brew.rb", "brew.sh")
            } }
            if (isBrew) return create(ProgramIdentity(ProgramKind.TERMINAL, "brew", "brew"))
            for (candidate in candidates) {
                val path = candidate.replace('\\', '/').lowercase(Locale.ROOT)
                val name = path.substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd")
                val kind = when {
                    name in listOf("claude", "claude-code", "claude-code.js") || path.contains("/@anthropic-ai/claude-code/") -> ProgramKind.CLAUDE
                    name in listOf("codex", "codex.js", "@openai/codex") || path.contains("/@openai/codex/") || codexRegex.matches(name) -> ProgramKind.CODEX
                    name in listOf("antigravity", "agy", "antigravity-cli") || name.startsWith("antigravity-") || path.contains("/antigravity") || path.contains("/.gemini/antigravity") -> ProgramKind.ANTIGRAVITY
                    name in listOf("nvim", "neovim") -> ProgramKind.NEOVIM
                    name in listOf("vim", "vi", "gvim") -> ProgramKind.VIM
                    name in listOf("git", "lazygit", "tig") -> ProgramKind.GIT
                    name == "python" || pythonRegex.matches(name) || name == "ipython" -> ProgramKind.PYTHON
                    name in listOf("node", "nodejs", "node.js", "npm", "pnpm", "yarn", "bun", "deno") -> ProgramKind.NODE
                    name in listOf("ssh", "mosh", "mosh-client") -> ProgramKind.SSH
                    name in listOf("tmux", "zellij", "screen") -> ProgramKind.TMUX
                    name in listOf("top", "htop", "btop") -> ProgramKind.MONITOR
                    name in listOf("cloudflared", "cloudflare") -> ProgramKind.CLOUDFLARE
                    name in listOf("rex", "rex-server", "rex-cli") || rexRegex.matches(name) -> ProgramKind.REX
                    else -> null
                }
                if (kind != null) return create(ProgramIdentity(kind, when(kind) {
                    ProgramKind.CLAUDE -> "Claude Code"; ProgramKind.CODEX -> "Codex"; ProgramKind.ANTIGRAVITY -> "Antigravity"
                    ProgramKind.NEOVIM -> "Neovim"; ProgramKind.VIM -> "Vim"; ProgramKind.GIT -> "Git"
                    ProgramKind.PYTHON -> "Python"; ProgramKind.NODE -> "JavaScript runtime"
                    ProgramKind.SSH -> "Remote shell"; ProgramKind.TMUX -> "Terminal multiplexer"
                    ProgramKind.MONITOR -> "System monitor"; ProgramKind.CLOUDFLARE -> "Cloudflare"; ProgramKind.REX -> "Rex"; ProgramKind.TERMINAL -> "Terminal"
                },name))
            }
            val nonShell = candidates.map { it.replace('\\', '/').substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd") }
                .firstOrNull { it.lowercase(Locale.ROOT) !in shellNames && it.isNotBlank() }
            val rawName = (nonShell ?: candidates.firstOrNull()?.replace('\\', '/')?.substringAfterLast('/')?.trimStart('-'))
                ?.filterNot { it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt() }?.take(40).orEmpty()
            return create(ProgramIdentity(ProgramKind.TERMINAL, rawName.ifBlank { "Terminal" }, rawName))
        }
    }
}
