package dev.lemon.rex

import android.content.Context
import org.json.JSONArray

data class AppTheme(val id: String,val name: String,val terminal: TerminalPalette,val ui: Map<String,Int> = emptyMap())
internal object ThemeCatalog {
    val defaults=listOf(AppTheme("rexlink-default-dark","Rexling Default",TerminalPalette.Dark),AppTheme("rexlink-default-light","Rexling Default",TerminalPalette.Light))
    @Volatile private var cached: List<AppTheme>?=null
    fun load(context: Context): List<AppTheme> = cached ?: synchronized(this) {
        cached ?: parse(context.assets.open("themes/rex-builtins.json").bufferedReader().use {it.readText()}).also {cached=it}
    }
    private val hex=Regex("#[0-9a-fA-F]{6}")
    private fun color(value: String): Int {require(hex.matches(value));return (value.drop(1).toLong(16) or 0xff000000L).toInt()}
    fun parse(text: String): List<AppTheme> {
        val rows=JSONArray(text);require(rows.length()<=256)
        val themes=(0 until rows.length()).map {index->
            val row=rows.getJSONObject(index);val id=row.getString("id");val name=row.getString("name")
            require(id.matches(Regex("[a-z0-9-]{1,80}")) && name.length in 1..80)
            val ansi=row.getJSONArray("ansi");require(ansi.length()==256)
            val palette=TerminalPalette(row.getBoolean("dark"),color(row.getString("background")),color(row.getString("foreground")),
                color(row.getString("cursor")),List(256){color(ansi.getString(it))},color(row.getString("cursorText")))
            val ui=row.getJSONObject("ui");val colors=ui.keys().asSequence().associateWith {color(ui.getString(it))}
            require(listOf("canvas","surface","surfaceSubtle","surfaceRaised","strokeSubtle","stroke","content","contentSecondary","accentSolid","accentOnSolid","accentContainer","accentOnContainer","dangerSolid","dangerOnSolid","dangerContainer","dangerOnContainer").all {it in colors})
            AppTheme(id,name,palette,colors)
        }
        require((defaults+themes).map {it.id}.distinct().size==defaults.size+themes.size)
        return defaults+themes
    }
    fun resolve(themes: List<AppTheme>,dark: Boolean,id: String): AppTheme = themes.firstOrNull {it.id==id && it.terminal.dark==dark} ?: defaults.first {it.terminal.dark==dark}
}
