package dev.shulhi.rex

internal data class ProgramArtworkIndex(
    val icons: Set<String>,
    val glyphs: Set<String>,
    val skins: Map<String, ProgramSkin>,
    val aliases: Map<String, String>,
) {
    fun icon(value: String): String? {
        val name = executableName(value)
        if (name.isBlank()) return null
        val clean = name.replace('-', '_')
        return (aliases[name] ?: aliases[clean] ?: clean).takeIf(icons::contains)
    }

    fun glyph(value: String): String? {
        val name = executableName(value)
        if (name.isBlank()) return null
        val clean = name.replace('-', '_')
        return (aliases[name] ?: aliases[clean] ?: clean).takeIf(glyphs::contains)
    }
}

internal data class ProgramArtwork(val tile: String?, val glyph: String?, val source: Source) {
    enum class Source { SKIN_VARIANT, SKIN_DEFAULT, EXECUTABLE, CANDIDATE, SHELL, GENERATED }
}

internal fun interface ProgramArtworkRule {
    fun resolve(program: ProgramIdentity, index: ProgramArtworkIndex): ProgramArtwork?
}

internal object ProgramArtworkResolver {
    private val rules = listOf(
        ProgramArtworkRule(::skinVariant),
        ProgramArtworkRule(::skinDefault),
        ProgramArtworkRule(::executable),
        ProgramArtworkRule(::candidate),
        ProgramArtworkRule(::shell),
        ProgramArtworkRule(::generated),
    )

    fun resolve(program: ProgramIdentity, index: ProgramArtworkIndex): ProgramArtwork =
        rules.firstNotNullOfOrNull { it.resolve(program, index) }
            ?: ProgramArtwork(null, index.glyph("terminal"), ProgramArtwork.Source.GENERATED)

    private fun skinVariant(program: ProgramIdentity, index: ProgramArtworkIndex): ProgramArtwork? {
        val skin = index.skins[program.kind.name] ?: return null
        val tile = skin.variants[executableName(program.executable)]?.takeIf(index.icons::contains) ?: return null
        val glyph = index.glyph(tile) ?: skin.glyph?.takeIf(index.glyphs::contains)
        return ProgramArtwork(tile, glyph, ProgramArtwork.Source.SKIN_VARIANT)
    }

    private fun skinDefault(program: ProgramIdentity, index: ProgramArtworkIndex): ProgramArtwork? {
        val skin = index.skins[program.kind.name] ?: return null
        val tile = skin.tile?.takeIf(index.icons::contains) ?: return null
        val glyph = index.glyph(tile) ?: skin.glyph?.takeIf(index.glyphs::contains)
        return ProgramArtwork(tile, glyph, ProgramArtwork.Source.SKIN_DEFAULT)
    }

    private fun executable(program: ProgramIdentity, index: ProgramArtworkIndex): ProgramArtwork? {
        val tile = index.icon(program.executable)
        val glyph = index.glyph(program.executable)
        if (tile == null && glyph == null) return null
        return ProgramArtwork(tile, glyph, ProgramArtwork.Source.EXECUTABLE)
    }

    private fun candidate(program: ProgramIdentity, index: ProgramArtworkIndex): ProgramArtwork? {
        for (candidate in program.candidates) {
            val name = executableName(candidate)
            if (name in ProgramIdentity.shellNames || name in setOf("shell", "terminal")) continue
            val tile = index.icon(candidate)
            val glyph = index.glyph(candidate)
            if (tile != null || glyph != null) {
                return ProgramArtwork(tile, glyph, ProgramArtwork.Source.CANDIDATE)
            }
        }
        return null
    }

    private fun shell(program: ProgramIdentity, index: ProgramArtworkIndex): ProgramArtwork? {
        if (!program.isShell) return null
        val name = executableName(program.executable)
        val tile = (if (name == "nu") "nushell" else "shell").takeIf(index.icons::contains) ?: return null
        val glyph = index.glyph(tile) ?: index.glyph("terminal")
        return ProgramArtwork(tile, glyph, ProgramArtwork.Source.SHELL)
    }

    private fun generated(program: ProgramIdentity, index: ProgramArtworkIndex): ProgramArtwork =
        ProgramArtwork(null, index.glyph("terminal"), ProgramArtwork.Source.GENERATED)
}
