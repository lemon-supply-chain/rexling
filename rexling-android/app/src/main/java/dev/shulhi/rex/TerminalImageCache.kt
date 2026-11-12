package dev.shulhi.rex

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer

/**
 * Bitmaps for the images a terminal places on screen. A repainting program (a browser
 * drawing full-screen frames, say) replaces its image many times per second, so the
 * cache is bounded by pixels rather than by image count: a handful of frames at
 * 1408×1920 is already tens of megabytes.
 */
internal object TerminalImageCache {
    private const val TAG = "TerminalImageCache"
    private const val MAX_ENTRIES = 32
    private const val MAX_BYTES = 48L shl 20

    private data class Entry(val generation: Long, val bitmap: Bitmap)

    // Access order: the eldest entry is the least recently drawn.
    private val cache = object : LinkedHashMap<Int, Entry>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>?) = false
    }
    private var bytes = 0L
    private var active: Set<Int> = emptySet()

    private fun store(id: Int, entry: Entry) {
        cache.remove(id)?.let { bytes -= it.bitmap.byteCount }
        cache[id] = entry
        bytes += entry.bitmap.byteCount
    }

    private fun drop(id: Int) {
        val entry = cache.remove(id) ?: return
        bytes -= entry.bitmap.byteCount
        if (!entry.bitmap.isRecycled) entry.bitmap.recycle()
    }

    /** Keeps the cache inside its budgets, evicting images that are not on screen first. */
    private fun trim() {
        while (cache.size > MAX_ENTRIES || bytes > MAX_BYTES) {
            val victim = cache.entries.firstOrNull { it.key !in active }?.key
                ?: cache.entries.firstOrNull()?.key
                ?: return
            if (victim in active) Log.w(TAG, "Evicting an image that is still on screen; budget is too small for this program")
            drop(victim)
        }
    }

    /**
     * Copies a decoded frame's pixels straight out of the update packet, so a repainting
     * program never costs an extra multi-megabyte array on the way to the bitmap.
     */
    @Synchronized
    fun update(imageId: Int, generation: Long, width: Int, height: Int, pixels: ByteBuffer) {
        val expected = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0) * 4
        if (width <= 0 || height <= 0 || expected != pixels.remaining().toLong()) {
            Log.w(TAG, "Invalid payload for image id=$imageId: ${pixels.remaining()} bytes, ${width}x$height")
            return
        }
        val existing = cache[imageId]
        if (existing != null && existing.generation == generation && !existing.bitmap.isRecycled &&
            existing.bitmap.width == width && existing.bitmap.height == height) return
        val reusable = existing?.bitmap?.takeIf {
            !it.isRecycled && it.width == width && it.height == height
        }
        try {
            val bitmap = reusable ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(pixels)
            store(imageId, Entry(generation, bitmap))
            trim()
        } catch (error: OutOfMemoryError) {
            // Shed every image that is not on screen, then give up on this one.
            Log.w(TAG, "Out of memory caching image id=$imageId, evicting off-screen images")
            cache.entries.filter { it.key !in active }.map { it.key }.forEach(::drop)
            if (bytes > MAX_BYTES) trim()
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to cache image id=$imageId", error)
        }
    }

    @Synchronized
    fun get(imageId: Int): Bitmap? {
        val entry = cache[imageId] ?: return null
        if (entry.bitmap.isRecycled) {
            drop(imageId)
            return null
        }
        return entry.bitmap
    }

    /** Records which images the current screen needs, then trims to the budget. */
    @Synchronized
    fun prune(activeImageIds: Set<Int>) {
        active = activeImageIds
        trim()
    }

    @Synchronized
    fun clear() {
        cache.keys.toList().forEach(::drop)
        active = emptySet()
    }
}