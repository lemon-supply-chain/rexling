package dev.lemon.rex

import java.lang.management.ManagementFactory
import com.sun.management.ThreadMXBean
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in host measurements, never a timing assertion or a phone latency claim. */
class RenderWorkBenchmarkTest {
    @Volatile private var sink:Any?=null
    @Test fun changedLogicalLinesAndUnchangedPreviewCache() {
        assumeTrue(System.getenv("REXLINK_RENDER_BENCHMARK")=="1")
        val bean=ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled=true
        val thread=Thread.currentThread().id
        val results=mutableListOf<String>()
        fun measure(name:String,count:Int,work:(Int)->Any) {
            repeat(300){sink=work(it)}
            val bytes=bean.getThreadAllocatedBytes(thread);val start=System.nanoTime()
            repeat(count){sink=work(it)}
            val elapsed=System.nanoTime()-start;val allocated=bean.getThreadAllocatedBytes(thread)-bytes
            results+="\"$name\":{\"iterations\":$count,\"ns_per_call\":${elapsed.toDouble()/count},\"bytes_per_call\":${allocated.toDouble()/count}}"
        }
        fun frame(color:Int):TerminalScreen {
            val lines=List(30){y->List(80){x->TerminalCell(x,y,('A'.code+(x+y)%26).toChar().toString(),color,0,0,1)}}
            return TerminalScreen(80,30,null,null,-1,0,lines.flatten(),lines=lines)
        }
        val frames=listOf(frame(1),frame(2));val index=TerminalLinks.Index()
        measure("linkless_changed_screen",2000){index.update(frames[it%2])}
        val previews=(0..15).associate {"p$it" to frames[0]};val state=AppState(selectedBlock="live",screen=frames[0])
        measure("unchanged_preview_cache",100000){updatePaneFrames(previews,state,state)}
        val json="{"+results.joinToString(",")+"}"
        println(json);System.getenv("REXLINK_RENDER_BENCHMARK_OUTPUT")?.let {File(it).writeText(json+"\n")}
    }
}
