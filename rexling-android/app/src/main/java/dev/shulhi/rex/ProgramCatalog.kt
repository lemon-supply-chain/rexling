package dev.shulhi.rex

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal data class ProgramSkin(
    val tile: String?,
    val glyph: String?,
    val variants: Map<String, String>,
    val colors: List<Int>,
    val neutral: Boolean,
)

internal object ProgramCatalog {
    private data class Index(
        val iconKeys: Set<String>,
        val glyphKeys: Set<String>,
        val skins: Map<String, ProgramSkin>,
        val aliases: Map<String, String>,
    )

    private const val NULL_SENTINEL = "\u0000"
    private val fallback = ProgramSkin(null, "terminal", emptyMap(), emptyList(), true)
    private var index: Index? = null
    private var fullCatalog: JSONObject? = null
    private val tileCache = ConcurrentHashMap<ProgramIdentity, String>()
    private val glyphCache = ConcurrentHashMap<ProgramIdentity, String>()

    @Synchronized private fun index(assets: AssetManager): Index = index ?: run {
        val root = runCatching {
            assets.open("program_index.json").bufferedReader().use { JSONObject(it.readText()) }
        }.getOrElse {
            assets.open("program_tiles.json").bufferedReader().use { JSONObject(it.readText()) }
        }
        val iconKeys = when (val raw = root.opt("icons")) {
            is JSONArray -> (0 until raw.length()).mapTo(hashSetOf()) { raw.getString(it) }
            is JSONObject -> raw.keys().asSequence().toHashSet()
            else -> emptySet()
        }
        val glyphKeys = when (val raw = root.opt("glyphs")) {
            is JSONArray -> (0 until raw.length()).mapTo(hashSetOf()) { raw.getString(it) }
            is JSONObject -> raw.keys().asSequence().toHashSet()
            else -> emptySet()
        }
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
        Index(iconKeys, glyphKeys, skins, aliases).also { index = it }
    }

    @Synchronized private fun fullCatalog(assets: AssetManager): JSONObject = fullCatalog ?: run {
        assets.open("program_tiles.json").bufferedReader().use { JSONObject(it.readText()) }.also { fullCatalog = it }
    }

    private fun color(value: String) = 0xFF000000.toInt() or value.removePrefix("#").toInt(16)

    fun icons(assets: AssetManager): JSONObject = fullCatalog(assets).getJSONObject("icons")

    fun glyphs(assets: AssetManager): JSONObject = fullCatalog(assets).getJSONObject("glyphs")

    fun skin(program: ProgramIdentity, assets: AssetManager): ProgramSkin =
        index(assets).skins[program.kind.name] ?: fallback

    fun tile(program: ProgramIdentity, assets: AssetManager): String? {
        val cached = tileCache[program]
        if (cached != null) return if (cached == NULL_SENTINEL) null else cached
        val resolved = resolveTile(program, assets)
        tileCache[program] = resolved ?: NULL_SENTINEL
        return resolved
    }

    private fun resolveTile(program: ProgramIdentity, assets: AssetManager): String? {
        val catalog = index(assets)
        if (program.isShell) return "shell".takeIf(catalog.iconKeys::contains)
        val skin = catalog.skins[program.kind.name] ?: fallback
        fun matchTile(target: String): String? {
            val key = target.lowercase(Locale.ROOT).replace('\\', '/').substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd")
            if (key.isBlank() || key in ProgramIdentity.shellNames) return null
            val clean = key.replace('-', '_')
            val cand = skin.variants[key] ?: catalog.aliases[key] ?: catalog.aliases[clean] ?: clean
            return cand.takeIf(catalog.iconKeys::contains)
        }
        val direct = skin.variants[program.executable.lowercase(Locale.ROOT)] ?: skin.tile?.takeIf(catalog.iconKeys::contains) ?: matchTile(program.executable) ?: matchTile(program.label)
        if (direct != null && direct in catalog.iconKeys) return direct
        for (c in program.candidates) {
            val matched = matchTile(c)
            if (matched != null) return matched
        }
        return if (program.kind == ProgramKind.TERMINAL) "shell".takeIf(catalog.iconKeys::contains) else null
    }

    fun glyph(program: ProgramIdentity, assets: AssetManager): String? {
        val cached = glyphCache[program]
        if (cached != null) return if (cached == NULL_SENTINEL) null else cached
        val resolved = resolveGlyph(program, assets)
        glyphCache[program] = resolved ?: NULL_SENTINEL
        return resolved
    }

    private fun resolveGlyph(program: ProgramIdentity, assets: AssetManager): String? {
        val catalog = index(assets)
        val skin = catalog.skins[program.kind.name] ?: fallback
        val tile = tile(program, assets)
        if (tile != null && tile in catalog.glyphKeys) return tile
        if (!skin.glyph.isNullOrEmpty() && skin.glyph in catalog.glyphKeys) return skin.glyph
        for (c in listOf(program.executable, program.label) + program.candidates) {
            val key = c.lowercase(Locale.ROOT).replace('\\', '/').substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd")
            if (key.isBlank() || key in ProgramIdentity.shellNames) continue
            val clean = key.replace('-', '_')
            val cand = catalog.aliases[key] ?: catalog.aliases[clean] ?: clean
            if (cand in catalog.glyphKeys) return cand
        }
        return "terminal".takeIf(catalog.glyphKeys::contains)
    }
}
