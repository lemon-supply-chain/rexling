package dev.lemon.rex

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseShowcaseExclusionTest {
    private fun appDir(): File =
        listOf(File("."), File("app")).first { File(it, "build.gradle.kts").isFile }

    @Test
    fun showcaseImplementationLivesOnlyInDebugSourceSet() {
        val app = appDir()
        val debugFile = File(app, "src/debug/java/dev/lemon/rex/DebugShowcase.kt")
        assertTrue("Missing debug showcase implementation", debugFile.isFile)
        assertTrue(debugFile.readText().contains("class DebugShowcaseSession"))

        val releaseStub = File(app, "src/release/java/dev/lemon/rex/DebugShowcase.kt")
        assertTrue("Missing release showcase stub", releaseStub.isFile)
        val stubText = releaseStub.readText()
        assertTrue(stubText.contains("const val available = false"))
        assertFalse(stubText.contains("DebugShowcaseSession"))

        val forbiddenRoots = listOf("src/main", "src/release", "src/optimized", "src/profile")
        val offenders = forbiddenRoots.flatMap { root ->
            val dir = File(app, root)
            if (!dir.isDirectory) emptyList()
            else
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { it.readText().contains("DebugShowcaseSession") }
                    .map { "${root}/${it.relativeTo(dir)}" }
                    .toList()
        }
        assertTrue(
            "DebugShowcaseSession must never be in non-debug source sets: $offenders",
            offenders.isEmpty(),
        )

        val gradleText = File(app, "build.gradle.kts").readText()
        assertTrue(
            gradleText.contains(
                """getByName("optimized") { kotlin.directories.add("src/release/java") }""",
            ),
        )
        assertTrue(
            gradleText.contains(
                """getByName("profile") { kotlin.directories.add("src/release/java") }""",
            ),
        )
    }
}
