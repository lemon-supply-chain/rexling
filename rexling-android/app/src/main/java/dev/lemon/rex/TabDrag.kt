package dev.lemon.rex

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** Coordinates follow the list's main axis: logical x for LazyRow, y for LazyColumn. */
internal class TabDrag {
    var id by mutableStateOf<String?>(null); private set
    var order by mutableStateOf<List<String>>(emptyList()); private set
    var left by mutableStateOf(0f); private set
    var pointer by mutableStateOf(0f); private set
    var moved by mutableStateOf(false); private set
    private var original=emptyList<String>()
    private var width=0
    private var distance=0f
    fun start(ids: List<String>,list: LazyListState,x: Float): Boolean {
        val item=list.layoutInfo.visibleItemsInfo.firstOrNull {x>=it.offset && x<it.offset+it.size} ?: return false
        val key=item.key as? String ?: return false
        if(key !in ids) return false
        original=ids;order=ids;id=key;left=item.offset.toFloat();pointer=x;width=item.size;distance=0f;moved=false
        return true
    }
    fun move(dx: Float,slop: Float,list: LazyListState) {
        if(id==null) return
        left+=dx;pointer+=dx;distance+=dx
        if(abs(distance)>slop) moved=true
        if(moved) cross(dx,list)
    }
    fun cross(direction: Float,list: LazyListState) {
        val dragged=id ?: return
        val index=order.indexOf(dragged)
        val center=left+width/2f
        val candidates=list.layoutInfo.visibleItemsInfo.filter {it.key!=dragged && it.key in order}
        val target=if(direction>0) candidates.filter {order.indexOf(it.key)>index && center>it.offset+it.size/2f}.maxByOrNull {order.indexOf(it.key)}
            else if(direction<0) candidates.filter {order.indexOf(it.key)<index && center<it.offset+it.size/2f}.minByOrNull {order.indexOf(it.key)} else null
        if(target!=null) {
            val to=order.indexOf(target.key)
            order=order.toMutableList().apply {removeAt(index);add(to,dragged)}
        }
    }
    fun translation(key: String,list: LazyListState): Float = if(key==id && moved)
        left-(list.layoutInfo.visibleItemsInfo.firstOrNull {it.key==key}?.offset ?: left.toInt()) else 0f
    fun changed()=moved && order!=original
    fun cancel() {id=null;order=emptyList();moved=false}
}

/** Intercept held moves/up before a child tab's clickable consumes the release. */
internal suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTabDrag(
    start: (androidx.compose.ui.geometry.Offset)->Boolean,
    move: (androidx.compose.ui.geometry.Offset)->Unit,
    end: ()->Unit,
    cancel: ()->Unit,
) {
    awaitEachGesture {
        try {
            val down=awaitFirstDown(requireUnconsumed=false)
            val held=awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if(!start(held.position)) return@awaitEachGesture
            held.consume()
            while(true) {
                val event=awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val change=event.changes.firstOrNull {it.id==held.id}
                if(change==null || change.isConsumed || event.changes.any {it.id!=held.id && it.pressed}) {cancel();break}
                val delta=change.position-change.previousPosition
                change.consume()
                if(delta!=androidx.compose.ui.geometry.Offset.Zero) move(delta)
                if(!change.pressed) {end();break}
            }
        } catch(error: kotlinx.coroutines.CancellationException) {cancel();throw error}
    }
}
