package dev.shulhi.rex

import com.sun.management.ThreadMXBean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale

class IconMatchingBenchmarkTest {
    @Volatile private var sink: Any? = null

    @Test fun benchmarkIconMatchingPipeline() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id

        println("================ ICON MATCHING BENCHMARK ================")

        // 1. Full JSON vs Index JSON parse comparison
        val tilesFile = File("src/main/assets/program_tiles.json")
        val indexFile = File("src/main/assets/program_index.json")

        val tilesText = tilesFile.readText()
        val indexText = indexFile.readText()

        println("Asset File Sizes:")
        println("  program_tiles.json (Full Vector Commands) : ${tilesFile.length() / 1024} KB")
        println("  program_index.json (Lightweight Index)   : ${indexFile.length() / 1024} KB")

        fun measure(name: String, count: Int, warmup: Int = 10, work: (Int) -> Any?) {
            repeat(warmup) { sink = work(it) }
            val bytes = bean.getThreadAllocatedBytes(thread)
            val start = System.nanoTime()
            repeat(count) { sink = work(it) }
            val elapsed = System.nanoTime() - start
            val allocated = bean.getThreadAllocatedBytes(thread) - bytes
            val msPerCall = (elapsed.toDouble() / count) / 1_000_000.0
            val kbPerCall = (allocated.toDouble() / count) / 1024.0
            System.out.printf("  %-42s: %8.3f ms/call | %8.1f KB alloc\n", name, msPerCall, kbPerCall)
        }

        println("\n1. Initial Parse / Deserialization (Cold Load):")
        measure("Legacy: Full 2.8MB program_tiles.json", 10, warmup = 2) {
            val root = JSONObject(tilesText)
            val skins = root.getJSONObject("skins")
            val aliases = root.getJSONObject("aliases")
            root.getJSONObject("icons")
        }
        measure("Optimized: 5KB program_index.json", 10, warmup = 2) {
            val root = JSONObject(indexText)
            val iconKeys = when (val raw = root.opt("icons")) {
                is JSONArray -> (0 until raw.length()).mapTo(hashSetOf()) { raw.getString(it) }
                is JSONObject -> raw.keys().asSequence().toHashSet()
                else -> emptySet()
            }
            val glyphKeys = when (val raw = root.opt("glyphs")) {
                is JSONArray -> (0 until raw.length()).mapTo(hashSetOf()) { raw.getString(it) }
                is JSONObject -> raw.keys().asSequence().toHashSet()
                else -> emptySet()
            }
            val skins = root.getJSONObject("skins")
            val aliases = root.getJSONObject("aliases")
            Pair(iconKeys, glyphKeys)
        }

        // 2. ProgramIdentity.resolve Regex overhead
        val candidatesList = listOf(
            listOf("/usr/bin/codex-aarch64-unknown-linux-musl", "node"),
            listOf("/usr/local/bin/python3.12", "zsh"),
            listOf("rex-aarch64-unknown-linux-musl", "fish"),
            listOf("claude-code", "node"),
            listOf("/opt/homebrew/bin/nvim", "zsh"),
            listOf("git", "bash"),
            listOf("/opt/homebrew/bin/brew", "ruby"),
            listOf("cloudflared", "zsh"),
            listOf("docker", "fish"),
            listOf("cargo", "zsh")
        )

        println("\n2. ProgramIdentity.resolve Throughput (10,000 iterations):")
        measure("Optimized: Precompiled Regex", 10_000, warmup = 1_000) {
            ProgramIdentity.resolve(candidatesList[it % candidatesList.size])
        }

        // Legacy uncompiled regex emulation
        measure("Legacy: Regex compiled per call", 10_000, warmup = 1_000) {
            val cand = candidatesList[it % candidatesList.size]
            for (c in cand) {
                val path = c.replace('\\', '/').lowercase(Locale.ROOT)
                val name = path.substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd")
                Regex("codex-(aarch64|x86_64)-.*").matches(name)
                Regex("python[23](\\.\\d+)?").matches(name)
                Regex("rex-(aarch64|x86_64)-.*").matches(name)
            }
        }

        // 3. Tile & Glyph Matching Latency
        val programs = candidatesList.map { ProgramIdentity.resolve(it) }
        val index = JSONObject(indexText)
        val iconKeys = (0 until index.getJSONArray("icons").length()).mapTo(hashSetOf()) { index.getJSONArray("icons").getString(it) }
        val glyphKeys = (0 until index.getJSONArray("glyphs").length()).mapTo(hashSetOf()) { index.getJSONArray("glyphs").getString(it) }
        val aliases = index.getJSONObject("aliases").let { values -> values.keys().asSequence().associateWith(values::optString) }

        println("\n3. Icon Tile Matching Latency:")
        // Uncached match
        measure("Uncached (Cold) Tile Matching", 10_000, warmup = 500) {
            val program = programs[it % programs.size]
            val key = program.executable.lowercase(Locale.ROOT).replace('\\', '/').substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd")
            val clean = key.replace('-', '_')
            val cand = aliases[key] ?: aliases[clean] ?: clean
            cand.takeIf { it in iconKeys }
        }

        // Cached lookup (Memoized)
        val cache = HashMap<ProgramIdentity, String?>()
        programs.forEach { p ->
            val key = p.executable.lowercase(Locale.ROOT).replace('\\', '/').substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd")
            val clean = key.replace('-', '_')
            val cand = aliases[key] ?: aliases[clean] ?: clean
            cache[p] = cand.takeIf { it in iconKeys }
        }
        measure("Memoized (Hot Recomposition) Lookup", 10_000, warmup = 500) {
            cache[programs[it % programs.size]]
        }
        println("=========================================================")
    }
}
