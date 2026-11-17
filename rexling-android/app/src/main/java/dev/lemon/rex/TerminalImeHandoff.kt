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
internal class TerminalImeHandoff {
    data class Retained(val block: BlockItem,val frame: TerminalScreen)
    private data class Mounted(val block: BlockItem,val view: TerminalView)
    private val mounted=mutableMapOf<String,Mounted>()
    private var visible=emptySet<String>()
    private var retained by mutableStateOf<Retained?>(null)
    fun retainFor(blocks: List<BlockItem>): Retained? {
        val next=blocks.map {it.id}.toSet()
        if(next!=visible) {
            val focused=mounted.values.firstOrNull {it.view.hasFocus() &&
                ViewCompat.getRootWindowInsets(it.view)?.isVisible(WindowInsetsCompat.Type.ime())==true}
            retained=focused?.takeIf {it.block.id !in next}?.let {entry->entry.view.screen?.let {Retained(entry.block,it)}}
            visible=next
        }
        return retained?.takeUnless {it.block.id in next}
    }
    fun frame(id: String)=mounted[id]?.view?.screen
    fun mount(block: BlockItem,view: TerminalView) {mounted[block.id]=Mounted(block,view)}
    fun remove(id: String) {mounted.remove(id)}
    fun served(id: String,view: TerminalView) {
        if(retained?.block?.id==id || retained==null) return
        val imm=view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if(view.hasFocus() && imm.isActive(view)) retained=null
    }
}
