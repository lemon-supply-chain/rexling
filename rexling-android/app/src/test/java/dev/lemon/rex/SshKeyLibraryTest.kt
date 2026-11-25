package dev.lemon.rex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SshKeyLibraryTest {
    @Test
    fun oneSecretCanServeMultipleEndpointsWithoutDuplication() {
        val library = SshKeyLibrary()
        val id = library.add("Work", "SHA256:test", "ssh-ed25519", "encrypted-once")
        library.assign("ssh://a", id)
        library.assign("ssh://b", id)
        val restored = SshKeyLibrary(library.encode())
        assertEquals(id, restored.selected("ssh://a"))
        assertEquals(id, restored.selected("ssh://b"))
        assertEquals(1, restored.list().size)
        assertEquals("encrypted-once", restored.secret(id))
        assertEquals(2, restored.endpoints(id).size)
    }

    @Test
    fun importingTheSameIdentityReusesItsNameAndSecret() {
        val library = SshKeyLibrary()
        val id = library.add("Work", "SHA256:a", "ssh-rsa", "original-ciphertext")
        assertEquals(id, library.add("Another name", "SHA256:a", "ssh-rsa", "different-encryption"))
        assertEquals("Work", library.list().single().name)
        assertEquals("original-ciphertext", library.secret(id))
    }

    @Test
    fun renameKeepsBindingsAndDeletingOnlyClearsThatKeysReferences() {
        val library = SshKeyLibrary()
        val a = library.add("A", "a", "ssh-rsa", "cipher-a")
        val b = library.add("B", "b", "ssh-rsa", "cipher-b")
        library.assign("ssh://a", a)
        library.assign("ssh://a2", a)
        library.assign("ssh://b", b)
        library.rename(a, "Renamed")
        assertEquals(a, library.selected("ssh://a"))
        assertEquals("Renamed", SshKeyLibrary(library.encode()).list().first { it.id == a }.name)
        library.remove(a)
        assertNull(library.selected("ssh://a"))
        assertNull(library.selected("ssh://a2"))
        assertEquals(b, library.selected("ssh://b"))
        assertEquals("cipher-b", library.secret(b))
    }

    @Test
    fun forgettingAnEndpointKeepsItsReusableKey() {
        val library = SshKeyLibrary()
        val id = library.add("Work", "a", "ssh-rsa", "cipher")
        library.assign("ssh://a", id)
        library.assign("ssh://b", id)
        library.assign("ssh://a", null)
        assertNull(library.selected("ssh://a"))
        assertEquals(id, library.selected("ssh://b"))
        assertEquals(1, library.list().size)
    }

    @Test
    fun migrationDeduplicatesByPublicIdentityAndPreservesOriginalCiphertext() {
        val legacy =
            JSONObject()
                .put("ssh://user@a", "cipher-a")
                .put("ssh://user@b", "cipher-b")
                .put("ssh://user@c", "unreadable")
        val library =
            SshKeyLibrary.migrate(legacy) {
                if (it == "unreadable") error("metadata unavailable")
                else "same-fingerprint" to "ssh-ed25519"
            }
        assertEquals(library.selected("ssh://user@a"), library.selected("ssh://user@b"))
        assertEquals("cipher-a", library.secret(library.selected("ssh://user@a")!!))
        assertEquals("unreadable", library.secret(library.selected("ssh://user@c")!!))
        assertEquals(2, library.list().size)
    }

    @Test
    fun selectingNoKeyDoesNotDeleteTheKeyAndInvalidIdsAreRejected() {
        val library = SshKeyLibrary()
        val id = library.add("Work", "a", "ssh-rsa", "cipher")
        library.assign("ssh://a", id)
        library.assign("ssh://a", null)
        assertEquals(1, library.list().size)
        assertNull(library.selected("ssh://a"))
        assertTrue(runCatching { library.assign("ssh://a", "missing") }.isFailure)
        assertTrue(runCatching { library.rename(id, "") }.isFailure)
        assertTrue(runCatching { library.add("x".repeat(81), "b", "ssh-rsa", "cipher") }.isFailure)
    }
}
