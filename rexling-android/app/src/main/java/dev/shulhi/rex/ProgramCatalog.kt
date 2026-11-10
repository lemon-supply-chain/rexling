package dev.shulhi.rex

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal data class ProgramSkin(
    val tile: String?,
    val glyph: String?,
    val variants: Map<String, String>,
    val colors: List<Int>,
    val neutral: Boolean,
)

internal object ProgramCatalog {
    private val fallback = ProgramSkin(null, "terminal", emptyMap(), emptyList(), true)
    private var index: ProgramArtworkIndex? = null
    private var fullCatalog: JSONObject? = null
    private val artworkCache = ConcurrentHashMap<ProgramIdentity, ProgramArtwork>()

    @Synchronized internal fun index(assets: AssetManager): ProgramArtworkIndex = index ?: run {
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
        ProgramArtworkIndex(iconKeys, glyphKeys, skins, aliases).also { index = it }
    }

    @Synchronized private fun fullCatalog(assets: AssetManager): JSONObject = fullCatalog ?: run {
        assets.open("program_tiles.json").bufferedReader().use { JSONObject(it.readText()) }.also { fullCatalog = it }
    }

    private fun color(value: String) = 0xFF000000.toInt() or value.removePrefix("#").toInt(16)

    fun icons(assets: AssetManager): JSONObject = fullCatalog(assets).getJSONObject("icons")

    fun glyphs(assets: AssetManager): JSONObject = fullCatalog(assets).getJSONObject("glyphs")

    fun skin(program: ProgramIdentity, assets: AssetManager): ProgramSkin =
        index(assets).skins[program.kind.name] ?: fallback

    fun artwork(program: ProgramIdentity, assets: AssetManager): ProgramArtwork =
        artworkCache.getOrPut(program) { ProgramArtworkResolver.resolve(program, index(assets)) }

    fun tile(program: ProgramIdentity, assets: AssetManager): String? =
        artwork(program, assets).tile

    fun glyph(program: ProgramIdentity, assets: AssetManager): String? =
        artwork(program, assets).glyph ?: index(assets).glyph("terminal")

    fun iconKeys(assets: AssetManager): Set<String> = index(assets).icons
}
