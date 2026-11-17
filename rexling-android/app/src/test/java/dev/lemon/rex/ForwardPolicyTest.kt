package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class ForwardPolicyTest {
    @Test fun loopbackLinksPreservePathsQueriesAndFragmentsWithoutDoubleEncoding() {
        val link=LoopbackLink.parse("http://localhost:5173/a%2Fb?q=hello%20world#section")!!
        assertEquals("127.0.0.1",link.host);assertEquals(5173,link.port)
        assertEquals("http://127.0.0.1:45173/a%2Fb?q=hello%20world#section",link.forwarded("127.0.0.1:45173"))
        assertEquals(443,LoopbackLink.parse("https://127.0.0.1")!!.port)
        assertEquals("::1",LoopbackLink.parse("http://[::1]:8080/")!!.host)
    }
    @Test fun onlyLiteralLoopbackWebLinksAreForwarded() {
        for(url in listOf("http://localhost.evil.test:3000/","http://example.com/","http://192.168.1.1/","file://localhost/a","http://user:secret@localhost:3000/","http://localhost:0/","http://localhost:65536/")) assertNull(url,LoopbackLink.parse(url))
        assertEquals(PortConflictPolicy.ASK,PortConflictPolicy.decode(null))
        assertEquals(PortConflictPolicy.ASK,PortConflictPolicy.decode("unknown"))
    }
}
