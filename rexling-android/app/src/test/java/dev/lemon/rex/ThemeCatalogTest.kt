package dev.lemon.rex

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ThemeCatalogTest {
    private val themes=ThemeCatalog.parse(sequenceOf(File("src/main/assets/themes/rex-builtins.json"),File("app/src/main/assets/themes/rex-builtins.json")).first {it.exists()}.readText())
    @Test fun includesAllPresetsAndUnchangedDefaults() {
        assertEquals(43,themes.size);assertEquals(28,themes.count {it.terminal.dark});assertEquals(15,themes.count {!it.terminal.dark})
        assertSame(TerminalPalette.Dark,themes[0].terminal);assertSame(TerminalPalette.Light,themes[1].terminal)
        assertTrue(themes.drop(2).all {it.terminal.ansi.size==256})
        val base=darkColorScheme();assertSame(base,base.withPreset(themes[0]))
    }
    @Test fun unknownAndWrongAppearanceSelectionsUseTheMatchingDefault() {
        assertEquals("rexlink-default-dark",ThemeCatalog.resolve(themes,true,"missing").id)
        assertEquals("rexlink-default-light",ThemeCatalog.resolve(themes,false,"merino-dark").id)
        assertEquals("merino-dark",ThemeCatalog.resolve(themes,true,"merino-dark").id)
    }
    @Test fun customThemesMapIndexedColorsButPreserveTrueColor() {
        val palette=themes.first {it.id=="merino-dark"}.terminal
        val indexed=TerminalCell(0,0,"X",123,456,512 or (200 shl 11),1)
        assertEquals(palette.ansi[200],palette.foreground(indexed))
        assertEquals(123,palette.foreground(indexed.copy(attributes=0)))
        val colors=darkColorScheme().withPreset(themes.first {it.id=="merino-dark"})
        assertEquals(Color(themes.first {it.id=="merino-dark"}.ui.getValue("canvas")),colors.background)
    }
    @Test fun invalidCatalogColorsAreRejected() {
        val text=File(if(File("src/main/assets/themes/rex-builtins.json").exists()) "src/main/assets/themes/rex-builtins.json" else "app/src/main/assets/themes/rex-builtins.json").readText()
        try {ThemeCatalog.parse(text.replaceFirst("#","bad"));fail("Invalid color accepted")} catch(_: IllegalArgumentException) {}
    }
}
