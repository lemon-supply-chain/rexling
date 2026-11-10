package dev.shulhi.rex

import android.content.res.AssetManager
import org.json.JSONObject
import java.util.Locale

internal data class ProgramSkin(
    val tile: String?,
    val glyph: String?,
    val variants: Map<String, String>,
    val colors: List<Int>,
    val neutral: Boolean,
)

internal object ProgramCatalog {
    private data class Catalog(
        val icons: JSONObject,
        val glyphs: JSONObject,
        val skins: Map<String, ProgramSkin>,
        val aliases: Map<String, String>,
    )

    private val fallback = ProgramSkin(null, "terminal", emptyMap(), emptyList(), true)
    private var catalog: Catalog? = null

    @Synchronized private fun catalog(assets: AssetManager): Catalog = catalog ?: run {
        val root = assets.open("program_tiles.json").bufferedReader().use { JSONObject(it.readText()) }
        val skins = root.getJSONObject("skins").let { values ->
            values.keys().asSequence().associateWith { key ->
                val value = values.getJSONObject(key)
                val variants = value.optJSONObject("variants")?.let { entries ->
                    entries.keys().asSequence().associateWith(entries::optString)
                } ?: emptyMap()
                val colors = value.optJSONArray("colors")?.let { stops ->
                    (0 until stops.length()).map { color(stops.getString(it)) }
                } ?: emptyList()
                ProgramSkin(
                    tile = value.optString("tile").takeIf(String::isNotEmpty),
                    glyph = value.optString("glyph").takeIf(String::isNotEmpty),
                    variants = variants,
                    colors = colors,
                    neutral = value.optBoolean("neutral"),
                )
            }
        }
        val aliases = root.getJSONObject("aliases").let { values ->
            values.keys().asSequence().associateWith(values::optString)
        }
        Catalog(root.getJSONObject("icons"), root.getJSONObject("glyphs"), skins, aliases).also { catalog = it }
    }

    private fun color(value: String) = 0xFF000000.toInt() or value.removePrefix("#").toInt(16)

    fun icons(assets: AssetManager): JSONObject = catalog(assets).icons

    fun glyphs(assets: AssetManager): JSONObject = catalog(assets).glyphs

    fun skin(program: ProgramIdentity, assets: AssetManager): ProgramSkin =
        catalog(assets).skins[program.kind.name] ?: fallback

    fun tile(program: ProgramIdentity, assets: AssetManager): String? {
        val catalog = catalog(assets)
        if (program.isShell) return "shell".takeIf(catalog.icons::has)
        val skin = catalog.skins[program.kind.name] ?: fallback
        fun matchTile(target: String): String? {
            val key = target.lowercase(Locale.ROOT).replace('\\', '/').substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd")
            if (key.isBlank() || key in ProgramIdentity.shellNames) return null
            val clean = key.replace('-', '_')
            val cand = skin.variants[key] ?: catalog.aliases[key] ?: catalog.aliases[clean] ?: clean
            return cand.takeIf(catalog.icons::has)
        }
        val direct = skin.variants[program.executable.lowercase(Locale.ROOT)] ?: skin.tile?.takeIf(catalog.icons::has) ?: matchTile(program.executable) ?: matchTile(program.label)
        if (direct != null && catalog.icons.has(direct)) return direct
        for (c in program.candidates) {
            val matched = matchTile(c)
            if (matched != null) return matched
        }
        return if (program.kind == ProgramKind.TERMINAL) "shell".takeIf(catalog.icons::has) else null
    }

    fun glyph(program: ProgramIdentity, assets: AssetManager): String? {
        val catalog = catalog(assets)
        val skin = catalog.skins[program.kind.name] ?: fallback
        val tile = tile(program, assets)
        if (tile != null && catalog.glyphs.has(tile)) return tile
        if (!skin.glyph.isNullOrEmpty() && catalog.glyphs.has(skin.glyph)) return skin.glyph
        for (c in listOf(program.executable, program.label) + program.candidates) {
            val key = c.lowercase(Locale.ROOT).replace('\\', '/').substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd")
            if (key.isBlank() || key in ProgramIdentity.shellNames) continue
            val clean = key.replace('-', '_')
            val cand = catalog.aliases[key] ?: catalog.aliases[clean] ?: clean
            if (catalog.glyphs.has(cand)) return cand
        }
        return "terminal".takeIf(catalog.glyphs::has)
    }
}
