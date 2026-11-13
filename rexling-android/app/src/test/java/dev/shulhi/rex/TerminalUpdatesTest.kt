package dev.shulhi.rex

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class TerminalUpdatesTest {
    private fun packet(revision: Long,flag: Int,changed: Map<Int,String>,metadata: String="",version: Int=1,links: Map<Int,List<Triple<Int,Int,String>>> = emptyMap(),payloads: List<TerminalImagePayload> = emptyList(),placements: List<TerminalImagePlacement> = emptyList(),shift: Int=0): ByteArray {
        val out=ByteArrayOutputStream()
        fun number(value: Long,size: Int) {repeat(size){out.write((value ushr (8*it)).toInt() and 255)}}
        fun text(value: String) {val bytes=value.toByteArray();number(bytes.size.toLong(),4);out.write(bytes)}
        out.write("RXT$version".toByteArray());number(revision,8);text(metadata);number(flag.toLong(),1)
        if(flag>=2) {
            number(2,2);number(2,2);number(-1,4);number(-1,4);number(-1,4);number(0xff000000,4)
            repeat(3){number(2,8)};if(version>=2) number(3,1)
            if(version>=5) number(shift.toLong(),2)
            number(changed.size.toLong(),2)
            changed.forEach {(row,value)->
                number(row.toLong(),2);number(1,2);number(0,2);number(1,1);number(0,4);number(-1,4);number(0xff000000,4);text(value)
                if(version>=3) {val ranges=links[row].orEmpty();number(ranges.size.toLong(),2);for((start,end,url) in ranges) {number(start.toLong(),2);number(end.toLong(),2);text(url)}}
            }
            if(version>=4) {
                number(payloads.size.toLong(),2)
                for(p in payloads) {
                    number(p.imageId.toLong(),4);number(p.generation,8)
                    number(p.width.toLong(),4);number(p.height.toLong(),4)
                    number(p.pixels.size.toLong(),4);out.write(p.pixels)
                }
                number(placements.size.toLong(),2)
                for(pl in placements) {
                    number(pl.imageId.toLong(),4)
                    number(pl.col.toLong(),4);number(pl.row.toLong(),4)
                    number(pl.cols.toLong(),4);number(pl.rows.toLong(),4)
                    number(pl.offsetX.toLong(),4);number(pl.offsetY.toLong(),4)
                    number(pl.width.toLong(),4);number(pl.height.toLong(),4)
                    number(pl.srcX.toLong(),4);number(pl.srcY.toLong(),4)
                    number(pl.srcWidth.toLong(),4);number(pl.srcHeight.toLong(),4)
                    number(pl.z.toLong(),4)
                }
            }
        }
        return out.toByteArray()
    }
    @Test fun deltasReuseUntouchedRowsAndPreserveUnicode() {
        val decoder=TerminalUpdates()
        val first=decoder.decode(packet(1,3,mapOf(0 to "λ",1 to "🦀"),"""{"phase":"connected","selectedBlock":"a"}"""))
        val second=decoder.decode(packet(4,2,mapOf(1 to "\uDB80\uDF1D")))
        assertSame(first.screen!!.lines[0],second.screen!!.lines[0])
        assertEquals("\uDB80\uDF1D",second.screen.lines[1][0].text)
        assertEquals("a",second.selectedBlock);assertEquals(4L,decoder.revision)
        val unchanged=decoder.decode(packet(5,0,emptyMap(),"""{"phase":"paused"}"""))
        assertSame(second.screen,unchanged.screen)
        assertNull(decoder.decode(packet(6,1,emptyMap())).screen)
    }
    @Test fun unknownBaseTruncationAndDuplicateRevisionAreRejectedWithoutAdvancing() {
        val decoder=TerminalUpdates()
        assertTrue(runCatching {decoder.decode(packet(1,2,mapOf(0 to "x")))}.isFailure)
        val full=packet(1,3,mapOf(0 to "x",1 to "y"))
        assertTrue(runCatching {decoder.decode(full.copyOf(full.size-1))}.isFailure)
        assertEquals(0L,decoder.revision)
        decoder.decode(full)
        assertTrue(runCatching {decoder.decode(full)}.isFailure)
        assertEquals(1L,decoder.revision)
    }
    @Test fun versionTwoCarriesApplicationInputModes() {
        val legacy=packet(1,3,mapOf(0 to "x",1 to "y"))
        val at=17+44 // frame flag/header, then input-mode byte before changed-row count
        val current=legacy.copyOfRange(0,at)+byteArrayOf(3)+legacy.copyOfRange(at,legacy.size)
        current[3]='2'.code.toByte()
        val frame=TerminalUpdates().decode(current).screen!!
        assertEquals(3,frame.inputFlags);assertTrue(frame.applicationScroll)
    }

    @Test fun versionThreeKeepsAndReplacesSparseHyperlinksWithoutChangingLabelText() {
        val decoder=TerminalUpdates()
        fun ranges(url: String)=mapOf(0 to listOf(Triple(0,1,url)))
        val first=decoder.decode(packet(1,3,mapOf(0 to "Docs",1 to "x"),version=3,links=ranges("https://example.com/one")))
        val second=decoder.decode(packet(2,2,mapOf(0 to "Docs"),version=3,links=ranges("https://example.com/two")))
        assertEquals("https://example.com/one",first.screen!!.lines[0][0].hyperlink)
        assertEquals("Docs",second.screen!!.lines[0][0].text)
        assertEquals("https://example.com/two",second.screen.lines[0][0].hyperlink)
        assertSame(first.screen.lines[1],second.screen.lines[1])
        assertNull(decoder.decode(packet(3,2,mapOf(0 to "Docs"),version=3)).screen!!.lines[0][0].hyperlink)
    }
    @Test fun invalidHyperlinkRangesDoNotAdvanceTheDecoder() {
        for(ranges in listOf(listOf(Triple(1,1,"https://example.com")),listOf(Triple(0,3,"https://example.com")),
            listOf(Triple(0,1,"https://a.example"),Triple(0,1,"https://b.example")))) {
            val decoder=TerminalUpdates()
            assertTrue(runCatching {decoder.decode(packet(1,3,mapOf(0 to "x",1 to "y"),version=3,links=mapOf(0 to ranges)))}.isFailure)
            assertEquals(0L,decoder.revision)
        }
    }

    @Test fun metadataOnlyTitleUpdatesReuseTheActivityCatalogUntilExplicitReplacement() {
        val decoder=TerminalUpdates()
        val first=decoder.decode(packet(1,1,emptyMap(),"""{"activity":[{"session":"s","block":"b","state":"blocked","title":"Task"}]}"""))
        val next=decoder.decode(packet(2,1,emptyMap(),"""{"titles":{"b":"New title"}}"""))
        assertSame(first.activity,next.activity)
        assertTrue(decoder.decode(packet(3,1,emptyMap(),"""{"activity":null}""")).activity.isEmpty())
    }

    @Test fun versionFourDecodesKittyImagesAndPixels() {
        val decoder=TerminalUpdates()
        val payload = TerminalImagePayload(42, 1L, 1, 1, byteArrayOf(255.toByte(), 0, 0, 255.toByte()))
        val placement = TerminalImagePlacement(42, 0, 0, 10, 5, 0, 0, 80, 80, 0, 0, 1, 1, 0)
        val first = decoder.decode(packet(1, 3, mapOf(0 to "x", 1 to "y"), version = 4, payloads = listOf(payload), placements = listOf(placement)))
        assertEquals(1, first.screen!!.imagePayloads.size)
        assertEquals(1, first.screen.imagePlacements.size)
        val decPayload = first.screen.imagePayloads[0]
        assertEquals(42, decPayload.imageId)
        assertEquals(1L, decPayload.generation)
        assertArrayEquals(byteArrayOf(255.toByte(), 0, 0, 255.toByte()), decPayload.pixels)
        val decPlacement = first.screen.imagePlacements[0]
        assertEquals(42, decPlacement.imageId)
        assertEquals(10, decPlacement.cols)
        assertEquals(5, decPlacement.rows)

        // Delta frame with no new payloads (cached), only placement moving:
        val cachedPlacement = placement.copy(col = 1)
        val second = decoder.decode(packet(2, 2, emptyMap(), version = 4, payloads = emptyList(), placements = listOf(cachedPlacement)))
        assertEquals(0, second.screen!!.imagePayloads.size)
        assertEquals(1, second.screen.imagePlacements.size)
        assertEquals(1, second.screen.imagePlacements[0].col)
    }

    @Test fun versionFourRejectsCorruptedPixelLength() {
        val decoder = TerminalUpdates()
        val out = ByteArrayOutputStream()
        fun number(value: Long, size: Int) { repeat(size) { out.write((value ushr (8 * it)).toInt() and 255) } }
        fun text(value: String) { val bytes = value.toByteArray(); number(bytes.size.toLong(), 4); out.write(bytes) }
        out.write("RXT4".toByteArray()); number(1, 8); text(""); number(3, 1)
        number(2, 2); number(2, 2); number(-1, 4); number(-1, 4); number(-1, 4); number(0xff000000, 4)
        repeat(3) { number(2, 8) }; number(3, 1); number(0, 2)
        number(1, 2)
        number(10, 4); number(1, 8); number(10, 4); number(10, 4)
        number(9999999, 4)
        number(0, 2)
        assertTrue(runCatching { decoder.decode(out.toByteArray()) }.isFailure)
        assertEquals(0L, decoder.revision)
    }

    @Test fun shiftedRowsKeepHyperlinksAndLocalImagePlacementFormat() {
        val decoder=TerminalUpdates()
        val payload=TerminalImagePayload(42,1,1,1,byteArrayOf(-1,0,0,-1))
        val placement=TerminalImagePlacement(42,0,1,1,1,0,0,8,16,0,0,1,1,0)
        decoder.decode(packet(1,3,mapOf(0 to "a",1 to "Docs"),version=5,
            links=mapOf(1 to listOf(Triple(0,1,"https://example.com"))),payloads=listOf(payload),placements=listOf(placement)))
        val shifted=decoder.decode(packet(2,2,mapOf(1 to "new"),version=5,shift=1,placements=listOf(placement.copy(row=0)))).screen!!
        assertEquals(listOf("Docs","new"),shifted.lines.map {it.single().text})
        assertEquals(listOf(0,1),shifted.cells.map {it.y})
        assertEquals("https://example.com",shifted.lines[0].single().hyperlink)
        assertTrue(shifted.imagePayloads.isEmpty())
        assertEquals(placement.copy(row=0),shifted.imagePlacements.single())
        val back=decoder.decode(packet(3,2,mapOf(0 to "above"),version=5,shift=-1)).screen!!
        assertEquals(listOf("above","Docs"),back.lines.map {it.single().text})
        assertEquals(1,back.lines[1].single().y)
    }

    @Test fun invalidShiftsDoNotAdvanceOrDestroyTheBase() {
        val decoder=TerminalUpdates()
        decoder.decode(packet(1,3,mapOf(0 to "a",1 to "b"),version=5))
        for(invalid in listOf(packet(2,2,emptyMap(),version=5,shift=1),
            packet(2,2,mapOf(0 to "x"),version=5,shift=-2),
            packet(2,3,mapOf(0 to "x",1 to "y"),version=5,shift=1))) {
            assertTrue(runCatching {decoder.decode(invalid)}.isFailure)
            assertEquals(1L,decoder.revision)
        }
        val valid=decoder.decode(packet(2,2,mapOf(1 to "c"),version=5,shift=1)).screen!!
        assertEquals(listOf("b","c"),valid.cells.map {it.text})
    }
}
