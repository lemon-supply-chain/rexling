package dev.lemon.rex

import android.view.Choreographer

internal data class TerminalDisplayLayout(val target: String="",val hasScreen: Boolean=false,val columns: Int=0,val rows: Int=0,val below: Boolean=false)

internal interface DisplayFrameClock {
    fun post(callback: ()->Unit)
    fun cancel(callback: ()->Unit)
}

/** Main-thread, bounded presentation slot. Protocol data never goes through it. */
internal class LatestDisplayFrame<T: Any>(private val clock: DisplayFrameClock,private val present: (T)->Unit) {
    private var latest: T?=null
    private var scheduled: (()->Unit)?=null
    fun offer(value: T) {
        latest=value
        if(scheduled!=null) return
        lateinit var callback: ()->Unit
        callback={
            if(scheduled===callback) {
                scheduled=null
                val value=latest;latest=null
                if(value!=null) present(value)
            }
        }
        scheduled=callback;clock.post(callback)
    }
    fun clear() {
        scheduled?.let(clock::cancel)
        scheduled=null;latest=null
    }
}

internal class AndroidDisplayFrameClock: DisplayFrameClock {
    private val clock=Choreographer.getInstance()
    private var pending: Pair<()->Unit,Choreographer.FrameCallback>?=null
    override fun post(callback: ()->Unit) {
        check(pending==null)
        lateinit var frame: Choreographer.FrameCallback
        frame=Choreographer.FrameCallback {
            if(pending?.second===frame) {pending=null;callback()}
        }
        pending=callback to frame;clock.postFrameCallback(frame)
    }
    override fun cancel(callback: ()->Unit) {
        pending?.takeIf {it.first===callback}?.let {pending=null;clock.removeFrameCallback(it.second)}
    }
}
