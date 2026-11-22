package dev.lemon.rex

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Keep at most one outgoing editor attached until the next editor is served.
 * This retains a view/frame only, never another remote stream or input ownership.
 */
internal class TerminalImeHandoff<Pane>(private val idOf: (Pane)->String) {
    data class Retained<Pane>(val pane: Pane,val frame: TerminalScreen)
    private data class Mounted<Pane>(val pane: Pane,val view: TerminalView)
    private val mounted=mutableMapOf<String,Mounted<Pane>>()
    private var visible=emptySet<String>()
    private var retained by mutableStateOf<Retained<Pane>?>(null)
    fun retainFor(panes: List<Pane>): Retained<Pane>? {
        if(panes.size!=visible.size || panes.any {idOf(it) !in visible}) {
            visible=panes.mapTo(hashSetOf(),idOf)
            val focused=mounted.values.firstOrNull {it.view.hasFocus() &&
                ViewCompat.getRootWindowInsets(it.view)?.isVisible(WindowInsetsCompat.Type.ime())==true}
            retained=focused?.takeIf {idOf(it.pane) !in visible}?.let {entry->entry.view.screen?.let {Retained(entry.pane,it)}}
        }
        return retained
    }
    fun frame(id: String)=mounted[id]?.view?.screen
    fun mount(pane: Pane,view: TerminalView) {mounted[idOf(pane)]=Mounted(pane,view)}
    fun remove(id: String) {mounted.remove(id)}
    fun served(id: String,view: TerminalView) {
        val outgoing=retained ?: return
        if(idOf(outgoing.pane)==id) return
        val imm=view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if(view.hasFocus() && imm.isActive(view)) retained=null
    }
}
