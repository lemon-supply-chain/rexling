package dev.lemon.rex

import java.util.Locale

internal data class ControlShortcut(val label: String,val keys: String) {
    val description: String get()=if(keys.length==1 && label=="^${keys.uppercase(Locale.ROOT)}") "Control ${keys.uppercase(Locale.ROOT)}" else "Control shortcut $label"
}

internal data class RecordedControlKey(val key: String,val control: Boolean=false,val shift: Boolean=false) {
    fun chip(index: Int)=(if(index==0 || control) "Ctrl+" else "")+(if(shift) "Shift+" else "")+if(key.length==1) key.uppercase(Locale.ROOT) else key
}

internal object ControlKeyRecorder {
    fun encode(keys: List<RecordedControlKey>)=keys.mapIndexed {index,key->
        (if(index>0 && key.control) "Ctrl + " else "")+(if(key.shift) "Shift + " else "")+key.key
    }.joinToString(" + ")
    fun decode(sequence: String): List<RecordedControlKey> {
        if(sequence.isBlank()) return emptyList()
        ControlShortcutConfig.decode(sequence)
        val tokens=sequence.split(Regex("\\s*\\+\\s*"));var index=0
        return buildList {
            while(index<tokens.size) {
                var token=tokens[index++].trim();var ctrl=false;var shift=false
                while(token.equals("Ctrl",true) || token.equals("Control",true) || token.equals("Shift",true)) {
                    if(token.equals("Shift",true)) shift=true else ctrl=true
                    token=tokens[index++].trim()
                }
                add(RecordedControlKey(token,ctrl,shift))
            }
        }
    }
    fun token(char: Char)=when(char) {' '->"Space";'+'->"Plus";'='->"Equals";'\n','\r'->"Enter";'\t'->"Tab";else->char.toString().lowercase(Locale.ROOT)}
    fun item(name: String,keys: List<RecordedControlKey>): ControlShortcut {
        require(keys.isNotEmpty()) {"Press at least one key."}
        val sequence=encode(keys)
        val label=name.trim().ifBlank {ControlShortcutConfig.label(sequence)}
        return ControlShortcutConfig.parse("$label = $sequence").single()
    }
}

/** The first key has Ctrl; later keys are plain unless explicitly prefixed Ctrl. */
internal object ControlShortcutConfig {
    val defaults=listOf("c","d","z","r","l","a","e","w","j","]").map {ControlShortcut("^${it.uppercase(Locale.ROOT)}",it)}
    private data class Step(val text: String,val display: String)
    fun editorText(items: List<ControlShortcut>)=items.joinToString("\n") {item->
        if(item.label==label(item.keys)) item.keys else "${item.label} = ${item.keys}"
    }
    fun parse(text: String): List<ControlShortcut> {
        require(text.length<=32768) {"The shortcut list is too long."}
        val items=text.lineSequence().map {it.trim()}.filter {it.isNotEmpty()}.map {line->
            val split=line.indexOf(" = ")
            val keys=(if(split<0) line else line.substring(split+3)).trim()
            require(keys.isNotEmpty() && keys.length<=256 && keys.none {it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt()}) {"Use a key sequence such as L or Space + L."}
            val generated=label(keys)
            val title=if(split<0) generated else line.take(split).trim()
            require(title.isNotEmpty() && title.length<=40 && title.none {it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt()}) {"Use a label of 1–40 characters. For longer sequences, use Name = keys."}
            ControlShortcut(title,keys)
        }.toList()
        require(items.isNotEmpty()) {"Add at least one Ctrl shortcut."}
        require(items.map {it.label}.distinct().size==items.size) {"Give each shortcut a different label."}
        return items
    }
    fun restore(text: String?): List<ControlShortcut> = if(text==null) defaults else runCatching {parse(text)}.getOrDefault(defaults)
    fun decode(keys: String)=steps(keys).joinToString("") {it.text}
    fun label(keys: String)=steps(keys).joinToString(" ") {it.display}
    private fun steps(keys: String): List<Step> {
        require(keys.isNotBlank() && keys.length<=256 && keys.none {it.isISOControl()}) {"Enter a key sequence."}
        val tokens=keys.split(Regex("\\s*\\+\\s*")).map {it.trim()}
        val result=mutableListOf<Step>();var index=0
        while(index<tokens.size) {
            var token=tokens[index++]
            var ctrl=result.isEmpty();var shift=false
            while(token.equals("ctrl",true) || token.equals("control",true) || token.equals("shift",true)) {
                if(token.equals("shift",true)) shift=true else ctrl=true
                require(index<tokens.size) {"Add a key after the modifier."}
                token=tokens[index++]
            }
            require(token.isNotBlank()) {"Add a key on each side of +. Use Plus for the + key."}
            val lower=token.lowercase(Locale.ROOT)
            val named=when(lower) {
                "space"->" ";"plus"->"+";"equals"->"="
                "escape","esc"->TerminalInput.key("esc")
                "return","enter"->TerminalInput.key("enter")
                "bs","backspace"->TerminalInput.key("backspace")
                "pgup","pageup"->TerminalInput.key("pageup")
                "pgdn","pagedown"->TerminalInput.key("pagedown")
                else->TerminalInput.key(lower)
            }
            val base=if(named.isNotEmpty()) named else token.takeIf {it.length==1}
            require(base!=null) {"Unknown key '$token'. Use a letter, Space, Tab, Enter or a navigation key."}
            val value=if(!ctrl) {
                if(token.length==1 && token[0].isLetter()) (if(shift) token.uppercase(Locale.ROOT) else token.lowercase(Locale.ROOT))
                else if(shift && named.isNotEmpty()) TerminalInput.key(lower,shift=true) else base
            } else when {
                base==" "->"\u0000"
                token.length==1 && token[0]=='?'->"\u007f"
                token.length==1 && token[0].uppercaseChar().code in 64..95->((token[0].uppercaseChar().code and 31).toChar()).toString()
                named.isNotEmpty() && lower !in listOf("plus","equals")->TerminalInput.key(when(lower) {
                    "return"->"enter";"escape"->"esc";"bs"->"backspace";"pgup"->"pageup";"pgdn"->"pagedown";else->lower
                },control=true,shift=shift)
                else->""
            }
            require(value.isNotEmpty()) {"Ctrl is not supported for '$token'."}
            val display=if(token.length==1) token.uppercase(Locale.ROOT) else token.replaceFirstChar {it.uppercase()}
            result+=Step(value,(if(ctrl) "^" else "")+(if(shift) "⇧" else "")+display)
        }
        return result
    }
}
