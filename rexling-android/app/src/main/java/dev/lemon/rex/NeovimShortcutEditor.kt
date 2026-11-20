package dev.lemon.rex

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class NeovimEditNode(val id: String=UUID.randomUUID().toString(),val group: String?=null,val shortcuts: List<NeovimShortcut>)
internal object NeovimShortcutEditor {
    fun fromItems(items: List<NeovimShortcut>)=NeovimShortcuts.entries(items).map {entry->when(entry) {
        is NeovimShortcutEntry.Action->NeovimEditNode(shortcuts=listOf(entry.shortcut))
        is NeovimShortcutEntry.Group->NeovimEditNode(group=entry.label,shortcuts=items.filter {it.group==entry.label})
    }}
    fun flatten(nodes: List<NeovimEditNode>)=nodes.flatMap {node->node.shortcuts.map {it.copy(group=node.group.orEmpty())}}
    fun groupName(name: String): String = name.trim().also {value->
        require(value.isNotEmpty() && value.length<=40 && !value.contains(" = ") && value.none {it=='[' || it==']' || it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt()}) {"Use a group name of 1–40 characters."}
    }
    fun validate(nodes: List<NeovimEditNode>,populated: Boolean=false): List<NeovimEditNode> {
        val groups=nodes.mapNotNull {it.group}
        groups.forEach(::groupName)
        require(groups.distinct().size==groups.size) {"Give each group a different name."}
        require(nodes.filter {it.group==null}.all {it.shortcuts.size==1}) {"A shortcut row must contain one shortcut."}
        if(populated) nodes.firstOrNull {it.group!=null && it.shortcuts.isEmpty()}?.let {error("Add a shortcut to ${it.group}, or remove the empty group.")}
        val items=flatten(nodes)
        require(items.none {it.group.isEmpty() && it.label in groups}) {"Give top-level shortcuts and groups different names."}
        require(NeovimShortcuts.parse(NeovimShortcuts.editorText(items))==items) {"Invalid shortcut configuration."}
        return nodes
    }
    fun upsert(nodes: List<NeovimEditNode>,source: String?,child: Int?,item: NeovimShortcut): List<NeovimEditNode> {
        val old=nodes.firstOrNull {it.id==source}
        val result=nodes.flatMap {node->if(node.id!=source) listOf(node) else if(node.group==null) emptyList() else listOf(node.copy(shortcuts=node.shortcuts.filterIndexed {index,_->index!=child}))}.toMutableList()
        if(item.group.isBlank()) {
            val at=if(old?.group==null && old!=null) nodes.indexOf(old).coerceAtMost(result.size) else result.size
            result.add(at,NeovimEditNode(id=old?.takeIf {it.group==null}?.id ?: UUID.randomUUID().toString(),shortcuts=listOf(item.copy(group=""))))
        } else {
            val at=result.indexOfFirst {it.group==item.group}
            require(at>=0) {"Choose an existing group."}
            val group=result[at];val children=group.shortcuts.toMutableList()
            val position=if(old?.group==item.group && child!=null) child.coerceAtMost(children.size) else children.size
            children.add(position,item);result[at]=group.copy(shortcuts=children)
        }
        return validate(result)
    }
    fun ungroup(nodes: List<NeovimEditNode>,id: String)=validate(nodes.flatMap {node->
        if(node.id!=id) listOf(node) else node.shortcuts.map {NeovimEditNode(shortcuts=listOf(it.copy(group="")))}
    })
    /** Editor sections do not change the root menu's existing group anchors. */
    fun reorderSection(nodes: List<NeovimEditNode>,groups: Boolean,ids: List<String>): List<NeovimEditNode> {
        val scoped=nodes.filter {(it.group!=null)==groups}
        require(ids.size==scoped.size && ids.toSet()==scoped.map {it.id}.toSet()) {"Invalid section order."}
        val ordered=ids.map {id->scoped.first {it.id==id}}.iterator()
        return nodes.map {if((it.group!=null)==groups) ordered.next() else it}
    }
    fun encode(nodes: List<NeovimEditNode>)=JSONArray().apply {nodes.forEach {node->put(JSONObject().put("id",node.id).put("group",node.group ?: JSONObject.NULL).put("shortcuts",JSONArray(NeovimShortcuts.encode(node.shortcuts))))}}.toString()
    fun decode(text: String): List<NeovimEditNode> {
        val array=JSONArray(text)
        return (0 until array.length()).map {index->array.getJSONObject(index).let {node->
            NeovimEditNode(node.getString("id"),if(node.isNull("group")) null else node.getString("group"),NeovimShortcuts.decode(node.getJSONArray("shortcuts").toString()))
        }}
    }
}

internal object NeovimKeyRecorder {
    fun tokens(keys: String): List<String> {
        NeovimShortcuts.decodeKeys(keys)
        val result=mutableListOf<String>();var at=0
        while(at<keys.length) {
            val end=if(keys[at]=='<') keys.indexOf('>',at+1) else -1
            if(end>at && keys.substring(at+1,end).none {it.isWhitespace()}) {result+=keys.substring(at,end+1);at=end+1}
            else result+=keys[at++].toString()
        }
        return result
    }
    fun char(char: Char)=when(char) {' '->"<Space>";'\n','\r'->"<CR>";'\t'->"<Tab>";'<'->"<lt>";else->char.toString()}
    fun label(token: String)=if(token.startsWith('<') && token.endsWith('>')) when(token.lowercase()) {
        "<cr>","<enter>","<return>"->"Enter";"<bs>","<backspace>"->"Backspace";"<del>"->"Delete";"<lt>"->"<";"<s-tab>"->"Shift+Tab"
        else->token.drop(1).dropLast(1).replace("C-","Ctrl+",true).replace("A-","Alt+",true)
    } else token
}
