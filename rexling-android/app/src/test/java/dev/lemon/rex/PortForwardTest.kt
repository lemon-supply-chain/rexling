package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class PortForwardTest {
    @Test fun loopbackAndTheRexServerHostAreForwardable() {
        assertTrue(PortForwardLinks.forwardable("localhost","unix:///tmp/rex.sock"))
        assertTrue(PortForwardLinks.forwardable("127.0.0.1","https://rex.example.ts.net"))
        assertTrue(PortForwardLinks.forwardable("127.0.0.53","https://rex.example.ts.net"))
        assertTrue(PortForwardLinks.forwardable("::1","https://rex.example.ts.net"))
        assertTrue(PortForwardLinks.forwardable("rex.example.ts.net","https://rex.example.ts.net"))
        assertTrue(PortForwardLinks.forwardable("REX.example.ts.net","https://rex.example.ts.net"))
    }
    @Test fun internetHostsAreLeftToTheBrowser() {
        assertFalse(PortForwardLinks.forwardable("example.com","https://rex.example.ts.net"))
        assertFalse(PortForwardLinks.forwardable("10.0.0.2","unix:///tmp/rex.sock"))
        assertFalse(PortForwardLinks.forwardable("127.example.com","unix:///tmp/rex.sock"))
        assertFalse(PortForwardLinks.forwardable("rex.example.ts.net.evil.com","https://rex.example.ts.net"))
    }
    @Test fun onlyPlainHttpWebLinksAreIntercepted() {
        assertNotNull(PortForwardLinks.target("http://localhost:5173/app","unix:///tmp/rex.sock"))
        assertNull(PortForwardLinks.target("https://localhost:5173/app","unix:///tmp/rex.sock"))
        assertNull(PortForwardLinks.target("http://example.com/","unix:///tmp/rex.sock"))
    }
    @Test fun explicitPortAndSchemeDefaultAreDistinct() {
        val explicit=PortForwardLinks.target("http://localhost:5173/app?x=1#top","unix:///tmp/rex.sock")!!
        assertEquals(5173,explicit.explicitPort)
        assertNull(explicit.defaultPort)
        val implicit=PortForwardLinks.target("http://[::1]/app","unix:///tmp/rex.sock")!!
        assertNull(implicit.explicitPort)
        assertEquals(80,implicit.defaultPort)
        assertEquals("::1",implicit.host)
    }
    @Test fun rewriteChangesOnlyTheOrigin() {
        assertEquals(
            "http://127.0.0.1:49152/app?x=1#top",
            PortForwardLinks.rewrite("http://localhost:5173/app?x=1#top",49152)
        )
        assertEquals("http://127.0.0.1:49152/",PortForwardLinks.rewrite("http://rex.example.ts.net",49152))
        assertEquals(
            "http://127.0.0.1:49152/a%20b?q=%E2%9C%93",
            PortForwardLinks.rewrite("http://localhost/a%20b?q=%E2%9C%93",49152)
        )
    }
    @Test fun stateParsesPortChoicesAndRevision() {
        val state=PortForwardState.parse(7,"k", """
            {"revision":4,"phase":"awaiting_selection","outcome":null,"reason":"","failure":"",
             "error":"","authority":"complete","portMode":"event","targetHost":"127.0.0.1",
             "targetPort":0,"localPort":0,"localUrl":"","tunnelBlock":"","connections":0,
             "choices":[{"port":5173,"address":"127.0.0.1","label":"node"},
                        {"port":8080,"address":"::1","label":"python"}]}
        """.trimIndent())!!
        assertEquals(4,state.revision.toInt())
        assertTrue(state.awaitingSelection)
        assertFalse(state.terminal)
        assertEquals(listOf(5173,8080),state.choices.map {it.port})
        assertEquals("python",state.choices[1].label)
    }
    @Test fun aClosedStateIsTerminalWithoutBeingAFailure() {
        val state=PortForwardState.parse(1,"k","""{"revision":9,"phase":"closed","outcome":"closed","reason":"port_gone","failure":"","error":"Port closed"}""")!!
        assertTrue(state.terminal)
        assertFalse(state.active)
        assertEquals("port_gone",state.reason)
        assertTrue(state.failure.isBlank())
    }
    @Test fun requestKeysSeparateExplicitAndAutomaticTargets() {
        val explicit=PortForwardRequest("e","s","b","localhost",5173,null,"http://localhost:5173/")
        val automatic=PortForwardRequest("e","s","b","localhost",null,80,"http://localhost/")
        assertNotEquals(explicit.key,automatic.key)
        assertTrue(explicit.json().contains("\"targetPort\":5173"))
        assertFalse(automatic.json().contains("targetPort"))
    }
}
