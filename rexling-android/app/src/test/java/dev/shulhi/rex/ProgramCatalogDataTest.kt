package dev.shulhi.rex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProgramCatalogDataTest {
    private val root=JSONObject(File("src/main/assets/program_tiles.json").readText())

    @Test fun skinsCoverEveryKindAndEveryTargetExists() {
        val icons=root.getJSONObject("icons")
        val glyphs=root.getJSONObject("glyphs")
        val skins=root.getJSONObject("skins")
        assertEquals(ProgramKind.values().map(ProgramKind::name).toSet(),skins.keys().asSequence().toSet())
        for(kind in skins.keys()) {
            val skin=skins.getJSONObject(kind)
            skin.optString("tile").takeIf(String::isNotEmpty)?.let {assertTrue("$kind tile $it",icons.has(it))}
            skin.optString("glyph").takeIf(String::isNotEmpty)?.let {assertTrue("$kind glyph $it",glyphs.has(it))}
            val variants=skin.optJSONObject("variants")
            variants?.keys()?.forEach {assertTrue("$kind variant $it",icons.has(variants.getString(it)))}
            skin.optJSONArray("colors")?.let {assertEquals("$kind colors",3,it.length())}
        }
        val aliases=root.getJSONObject("aliases")
        aliases.keys().forEach {assertTrue("alias $it",icons.has(aliases.getString(it)))}
    }

    @Test fun catalogKeepsTilesAndGlyphsPaired() {
        val icons=root.getJSONObject("icons")
        val glyphs=root.getJSONObject("glyphs")
        assertTrue(icons.has("docker"))
        assertTrue(glyphs.has("docker"))
        assertTrue(glyphs.has("claude"))
        assertTrue(glyphs.has("terminal"))
        assertTrue(glyphs.has("tmux"))
        assertTrue(glyphs.has("ssh"))
    }

    @Test fun indexMatchesTilesCatalog() {
        val indexFile = File("src/main/assets/program_index.json")
        assertTrue(indexFile.exists())
        val index = JSONObject(indexFile.readText())
        val icons = root.getJSONObject("icons")
        val glyphs = root.getJSONObject("glyphs")
        val indexIcons = index.getJSONArray("icons")
        val indexGlyphs = index.getJSONArray("glyphs")
        assertEquals(icons.length(), indexIcons.length())
        assertEquals(glyphs.length(), indexGlyphs.length())
        for (i in 0 until indexIcons.length()) {
            assertTrue(icons.has(indexIcons.getString(i)))
        }
        for (i in 0 until indexGlyphs.length()) {
            assertTrue(glyphs.has(indexGlyphs.getString(i)))
        }
    }
}
