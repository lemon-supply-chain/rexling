package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class QrScannerTest {
    @Test fun extractsHostAsLabelAndNormalizesServerUrl() {
        val result = parseQrServer("https://rex-mac.mau-coho.ts.net")
        assertNotNull(result)
        assertEquals("rex-mac", result!!.first)
        assertEquals("https://rex-mac.mau-coho.ts.net", result.second)
    }

    @Test fun stripsTrailingSlashAndHandlesRawHost() {
        val withSlash = parseQrServer("https://rex-mac.mau-coho.ts.net/")
        assertNotNull(withSlash)
        assertEquals("rex-mac", withSlash!!.first)
        assertEquals("https://rex-mac.mau-coho.ts.net", withSlash.second)

        val raw = parseQrServer("rex-mac.mau-coho.ts.net")
        assertNotNull(raw)
        assertEquals("rex-mac", raw!!.first)
        assertEquals("https://rex-mac.mau-coho.ts.net", raw.second)
    }

    @Test fun preservesIpAddressesAndPorts() {
        val ip = parseQrServer("http://192.168.1.100:8080")
        assertNotNull(ip)
        assertEquals("192.168.1.100", ip!!.first)
        assertEquals("http://192.168.1.100:8080", ip.second)
    }

    @Test fun rejectsInvalidQrStrings() {
        assertNull(parseQrServer(""))
        assertNull(parseQrServer("   "))
        assertNull(parseQrServer("ftp://something"))
        assertNull(parseQrServer("just text without domain"))
    }
}
