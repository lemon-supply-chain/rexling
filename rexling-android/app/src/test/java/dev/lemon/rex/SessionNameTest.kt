package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class SessionNameTest {
    @Test
    fun namesUseUnicodeCharactersRatherThanUtf16CodeUnits() {
        assertNull(sessionNameError("🦀".repeat(128)))
        assertEquals("Use at most 128 characters", sessionNameError("🦀".repeat(129)))
    }

    @Test
    fun blankAndControlCharacterNamesAreRejected() {
        assertEquals("Enter a session name", sessionNameError("  "))
        assertEquals(
            "Session names cannot contain control characters",
            sessionNameError("bad\nname"),
        )
        assertNull(sessionNameError("  Coding λ  "))
    }
}
