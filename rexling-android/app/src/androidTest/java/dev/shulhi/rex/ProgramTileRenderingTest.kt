package dev.shulhi.rex

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ProgramTileRenderingTest {
    @get:Rule val ui=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets=InstrumentationRegistry.getInstrumentation().context.assets
    private val names=listOf("codex","claude_code","neovim","vim","git","python","node","shell","htop","btop","cloudflare")
    @Test fun expandedCatalogRendersEveryTileAtPhoneSizes() {
        val icons=org.json.JSONObject(context.assets.open("program_tiles.json").bufferedReader().use {it.readText()}).getJSONObject("icons")
        assertEquals(ProgramCatalog.tiles,icons.keys().asSequence().toSet())
        val sorted=ProgramCatalog.tiles.sorted();val cols=8;val tileWidth=100;val tileHeight=90
        val atlas=Bitmap.createBitmap(cols*tileWidth,((sorted.size+cols-1)/cols)*tileHeight,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(atlas);canvas.drawColor(Color.rgb(225,229,237))
        val labels=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=11f;color=Color.BLACK}
        for((index,name) in sorted.withIndex()) {
            val tile=Bitmap.createBitmap(66,54,Bitmap.Config.ARGB_8888)
            ProgramTilePicture.load(context.assets,name).draw(Canvas(tile),66f,54f)
            assertTrue("$name is blank",(0 until tile.height).any {y->(0 until tile.width).any {x->Color.alpha(tile.getPixel(x,y))>0}})
            val x=(index%cols)*tileWidth;val y=(index/cols)*tileHeight
            canvas.drawBitmap(tile,x+16f,y+4f,null);canvas.drawText(name.take(16),x+2f,y+76f,labels);tile.recycle()
        }
        File(context.cacheDir,"expanded-program-catalog.png").outputStream().use {atlas.compress(Bitmap.CompressFormat.PNG,100,it)}
        atlas.recycle()
    }
    @Test fun nativeVectorsKeepTheCatalogGeometryColorsAndLogoMasks() {
        val atlas=Bitmap.createBitmap(976,384*names.size,Bitmap.Config.ARGB_8888);val out=Canvas(atlas)
        val deltas=mutableListOf<String>()
        for((i,name) in names.withIndex()) {
            val reference=BitmapFactory.decodeStream(testAssets.open("program_tiles/$name.png"))
            val actual=Bitmap.createBitmap(488,384,Bitmap.Config.ARGB_8888)
            ProgramVectorPicture.load(context.assets,name).draw(Canvas(actual),488f,384f)
            assertSame(ProgramVectorPicture.load(context.assets,name),ProgramVectorPicture.load(context.assets,name))
            var difference=0L
            for(y in 0 until 384) for(x in 0 until 488) {
                val a=actual.getPixel(x,y);val b=reference.getPixel(x,y)
                // Compare visible premultiplied color rather than hidden RGB in transparent margins.
                for(channel in 0..2) {val shift=channel*8;difference+=abs(((a ushr shift and 255)*Color.alpha(a)/255)-((b ushr shift and 255)*Color.alpha(b)/255))}
                difference+=abs(Color.alpha(a)-Color.alpha(b))
            }
            val mean=difference.toDouble()/(488*384*4);deltas+="$name=$mean"
            out.drawBitmap(reference,0f,i*384f,null);out.drawBitmap(actual,488f,i*384f,null)
            assertTrue("$name differs from the supplied tile: $mean",mean<12.0)
        }
        File(context.cacheDir,"program-tile-reference.png").outputStream().use {atlas.compress(Bitmap.CompressFormat.PNG,100,it)}
        File(context.cacheDir,"program-tile-deltas.txt").writeText(deltas.joinToString("\n"))
    }
    @Test fun programStacksKeepTheirSlotsAcrossOneTwoAndThreePanesAtPhoneSizes() {
        val programs=listOf(ProgramIdentity.resolve(listOf("/opt/homebrew/bin/cloudflared")),ProgramIdentity(ProgramKind.CLAUDE,"Claude Code"),ProgramIdentity(ProgramKind.NEOVIM,"Neovim"))
        ui.setContent {MaterialTheme {Column {
            for(count in 1..3) Row(Modifier.testTag("row$count")) {
                Box(Modifier.testTag("header$count")) {ProgramStack(programs.take(count),0,framed=true,fixedSlot=true)}
                Box(Modifier.testTag("tab$count")) {ProgramStack(programs.take(count),0)}
            }
        }}}
        val header=ui.onNodeWithTag("header1").fetchSemanticsNode().boundsInRoot
        for(count in 2..3) {
            val next=ui.onNodeWithTag("header$count").fetchSemanticsNode().boundsInRoot
            assertEquals(header.width,next.width,0f);assertEquals(header.height,next.height,0f)
        }
        ui.onNodeWithTag("header3").assertExists();ui.onNodeWithTag("tab3").assertExists()
        ui.waitForIdle()
        val stack=ui.onNodeWithTag("row3").captureToImage().asAndroidBitmap()
        File(context.cacheDir,"cloudflare-stacks.png").outputStream().use {stack.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun exportSmallSizeRotationComparisonForVisualReview() {
        val density=3f;val cellWidth=148;val cellHeight=110
        val atlas=Bitmap.createBitmap(cellWidth*6,cellHeight*names.size,Bitmap.Config.ARGB_8888);val canvas=Canvas(atlas)
        canvas.drawColor(Color.rgb(235,237,243))
        for((row,name) in names.withIndex()) for((column,config) in listOf(Triple(34,27,0f),Triple(34,27,-6f),Triple(22,18,-6f)).withIndex()) {
            val width=config.first*density;val height=config.second*density
            for(version in 0..1) {
                val save=canvas.save();canvas.translate((column*2+version)*cellWidth+16f,row*cellHeight+14f)
                if(version==0) {
                    val image=BitmapFactory.decodeStream(testAssets.open("program_tiles/$name.png"))
                    canvas.rotate(config.third,width/2,height/2)
                    canvas.drawBitmap(image,null,RectF(0f,0f,width,height),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                } else ProgramVectorPicture.load(context.assets,name).draw(canvas,width,height,config.third)
                canvas.restoreToCount(save)
            }
        }
        File(context.cacheDir,"program-tile-small.png").outputStream().use {atlas.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun catalogResolvesTilesGlyphsVariantsAndAliases() {
        fun tile(vararg names: String)=ProgramCatalog.tile(ProgramIdentity.resolve(names.toList()),context.assets)
        fun glyph(vararg names: String)=ProgramCatalog.glyph(ProgramIdentity.resolve(names.toList()),context.assets)
        assertEquals("claude_code",tile("claude-code"))
        assertEquals("claude",glyph("claude-code"))
        assertEquals("bun",tile("bun"))
        assertEquals("pnpm",tile("pnpm"))
        assertEquals("top",tile("top"))
        assertEquals("rust",tile("cargo"))
        assertEquals("shell",tile("fish"))
        assertEquals("terminal",glyph("fish"))
        assertEquals("tmux",glyph("tmux"))
        assertEquals("ssh",glyph("ssh"))
        assertEquals("terminal",glyph("unmapped-program"))
    }
    @Test fun glyphTintIsAppliedToNativePaths() {
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        ProgramVectorPicture.loadGlyph(context.assets,"claude",0xFFFF0000.toInt()).draw(Canvas(bitmap),64f,64f)
        val pixel=(0 until 64).asSequence().flatMap {y->(0 until 64).asSequence().map {x->bitmap.getPixel(x,y)}}.first {Color.alpha(it)>0}
        assertTrue(Color.red(pixel)>Color.green(pixel) && Color.red(pixel)>Color.blue(pixel))
    }
}
