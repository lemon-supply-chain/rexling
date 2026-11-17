package dev.lemon.rex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeImageUploadTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun main(action: ()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(predicate: ()->Boolean) {
        val deadline=android.os.SystemClock.uptimeMillis()+10000
        while(android.os.SystemClock.uptimeMillis()<deadline) {var done=false;main {done=predicate()};if(done) return;Thread.sleep(20)}
        error("Upload did not settle")
    }
    private class Peer(val welcome: Boolean=true,val chunkDelayMs: Long=0): AutoCloseable {
        val server=MockWebServer();val eof=CountDownLatch(1);val opened=CountDownLatch(1);val closed=CountDownLatch(1)
        val bytes=ByteArrayOutputStream();@Volatile var data:WebSocket?=null;@Volatile var target:JSONObject?=null
        init {
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val protocol=if(request.path=="/api/control") "rex.control.v1" else "rex.data.bytes.v1"
                    return MockResponse().setHeader("Sec-WebSocket-Protocol",protocol).withWebSocketUpgrade(object:WebSocketListener() {
                        override fun onOpen(ws:WebSocket,response:Response) {if(protocol!="rex.control.v1") {data=ws;opened.countDown()}}
                        override fun onMessage(ws:WebSocket,text:String) {
                            val value=JSONObject(text)
                            if(value.optString("type")=="hello") {
                                assertEquals(11,value.getInt("api_revision"))
                                ws.send("""{"type":"welcome","protocol":"rex.control.v1","api_revision":11,"limits":{"max_message_bytes":65536}}""")
                            } else {
                                val method=value.getString("method")
                                val payload=if(method=="session.attach") """{"view":{}}""" else {
                                    assertEquals("com.superlogical.terminal.upload_begin",method);target=value.getJSONObject("payload")
                                    """{"ticket":"AAAAAAAAAAAAAAAAAAAAAA","expires_in_ms":60000}"""
                                }
                                ws.send("""{"type":"response","id":"${value.getString("id")}","payload":$payload}""")
                            }
                        }
                        override fun onMessage(ws:WebSocket,value:ByteString) {
                            if(value.size==17 && value[0]==1.toByte()) {
                                if(welcome) {val reply=ByteArray(32);reply[0]=0x81.toByte();reply[24]=1;ws.send(ByteString.of(*reply))}
                            } else if(value.size==0) eof.countDown() else {
                                synchronized(bytes) {bytes.write(value.toByteArray())}
                                if(chunkDelayMs>0) Thread.sleep(chunkDelayMs)
                            }
                        }
                        override fun onClosing(ws:WebSocket,code:Int,reason:String) {ws.close(code,reason);closed.countDown()}
                        override fun onFailure(ws:WebSocket,t:Throwable,response:Response?) {closed.countDown()}
                    })
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"),0)
        }
        fun target()=ImageTarget("fixture","http://127.0.0.1:${server.port}","session:original","block:original",1,1,ProgramKind.CODEX,"Original pane")
        override fun close() {server.shutdown()}
    }
    @Test fun nativePickerUploadWaitsForConfirmationShowsSuccessThenDisappearsWithoutNotice() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);lateinit var uploads:ImageUploads;var released=0
        Peer().use {peer->try {
            main {uploads=ImageUploads(instrumentation.targetContext,scope);assertTrue(uploads.start(TestImageProvider.uri,peer.target()) {released++})}
            assertTrue(peer.eof.await(10,TimeUnit.SECONDS));await {uploads.state?.phase==ImagePhase.FINISHING}
            assertArrayEquals(TestImageProvider.png,synchronized(peer.bytes) {peer.bytes.toByteArray()})
            assertEquals("session:original",peer.target!!.getString("session_id"));assertEquals("block:original",peer.target!!.getString("block_id"))
            assertEquals(TestImageProvider.png.size.toLong(),peer.target!!.getJSONObject("args").getLong("size"))
            peer.data!!.close(1000,"")
            await {uploads.state?.phase==ImagePhase.SUCCESS}
            main {assertEquals("",uploads.notice)}
            await {uploads.state==null}
            main {
                assertEquals(1,released);assertEquals("",uploads.notice);assertFalse(uploads.retryable)
                assertTrue(uploads.start(TestImageProvider.uri,peer.target()))
                uploads.cancel()
            }
        } finally {main {uploads.close();scope.cancel()}}}
    }
    @Test fun lostFinalConfirmationDisablesRetry() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);lateinit var uploads:ImageUploads
        Peer().use {peer->try {
            main {uploads=ImageUploads(instrumentation.targetContext,scope);uploads.start(TestImageProvider.uri,peer.target())}
            assertTrue(peer.eof.await(10,TimeUnit.SECONDS));peer.server.shutdown();await {uploads.state?.phase==ImagePhase.FAILED}
            main {assertFalse(uploads.retryable);assertTrue(uploads.state!!.error.contains("Check the original pane"))}
        } finally {main {uploads.close();scope.cancel()}}}
    }
    @Test fun cancelClosesNativeUploadWhileWaitingForHandshake() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);lateinit var uploads:ImageUploads
        Peer(welcome=false).use {peer->try {
            main {uploads=ImageUploads(instrumentation.targetContext,scope);uploads.start(TestImageProvider.uri,peer.target())}
            assertTrue(peer.opened.await(10,TimeUnit.SECONDS));main {assertEquals(ImagePhase.PREPARING,uploads.state?.phase);uploads.cancel();assertNull(uploads.state)}
            assertTrue(peer.closed.await(5,TimeUnit.SECONDS));assertEquals(1L,peer.eof.count)
        } finally {main {uploads.close();scope.cancel()}}}
    }
    @Test fun unsupportedServerExplainsUpgradeWithoutAnotherUploadBackend() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);lateinit var uploads:ImageUploads
        try {
            main {
                uploads=ImageUploads(instrumentation.targetContext,scope,RexUploadBackend {_,_,_->RexUploadResult("unsupported","")})
                uploads.start(TestImageProvider.uri,ImageTarget("fixture","https://fixture","s","b",1,1,ProgramKind.CODEX,"Fixture"))
            }
            await {uploads.state?.phase==ImagePhase.FAILED}
            main {assertTrue(uploads.state!!.error.contains("Update the Rex server"))}
        } finally {main {uploads.close();scope.cancel()}}
    }

    @Test fun throttledNativeReceiverProducesIntermediateAndroidProgress() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val file=File(instrumentation.targetContext.cacheDir,"progress-fixture.png")
        file.writeBytes(TestImageProvider.png+ByteArray(512*1024))
        val updates=mutableListOf<Pair<String,Float>>();var result:RexUploadResult?=null
        Peer(chunkDelayMs=15).use {peer->try {
            main {scope.launch {result=RexImageUpload().upload(peer.target(),StagedImage(file,"fixture.png")) {phase,fraction->updates+=phase to fraction}}}
            assertTrue(peer.eof.await(10,TimeUnit.SECONDS))
            main {
                val intermediate=updates.filter {it.first=="sending" && it.second>0f && it.second<1f}
                assertTrue("Expected visible intermediate progress: $updates",intermediate.size>=3)
                assertTrue(intermediate.zipWithNext().all {(a,b)->b.second>=a.second})
            }
            peer.data!!.close(1000,"");await {result!=null}
            main {assertEquals("done",result!!.phase)}
        } finally {main {scope.cancel()};file.delete()}}
    }

}
