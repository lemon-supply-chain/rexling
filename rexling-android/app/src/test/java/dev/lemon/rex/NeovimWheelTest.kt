package dev.lemon.rex

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class NeovimWheelTest {
    @Test fun fanOutProgressTravelsClockwiseAndFinishesEverySegment() {
        val effect=NeovimShortcutEffect.FAN_OUT
        assertTrue(neovimWheelEffectProgress(effect,.4f,0,8)>neovimWheelEffectProgress(effect,.4f,3,8))
        assertTrue(neovimWheelEffectProgress(effect,.4f,3,8)>0f)
        assertEquals(0f,neovimWheelEffectProgress(effect,.4f,7,8),0f)
        for(slots in 1..8) for(index in 0 until slots) {
            assertEquals(0f,neovimWheelEffectProgress(effect,0f,index,slots),0f)
            assertEquals(1f,neovimWheelEffectProgress(effect,1f,index,slots),.00001f)
            val samples=(0..20).map {neovimWheelEffectProgress(effect,it/20f,index,slots)}
            assertTrue(samples.zipWithNext().all {(a,b)->a<=b})
        }
    }
    @Test fun simultaneousEffectsKeepEverySegmentTogether() {
        for(effect in listOf(NeovimShortcutEffect.SPIN,NeovimShortcutEffect.EXPAND)) {
            for(time in listOf(0f,.25f,.5f,.75f,1f)) {
                val first=neovimWheelEffectProgress(effect,time,0,8)
                for(index in 1..7) assertEquals(first,neovimWheelEffectProgress(effect,time,index,8),0f)
            }
            assertTrue(neovimWheelEffectProgress(effect,.5f,7,8)>neovimWheelEffectProgress(NeovimShortcutEffect.FAN_OUT,.5f,7,8))
        }
    }
    @Test fun removedShutterMigratesToExpandWithoutChangingOtherChoices() {
        assertEquals(NeovimShortcutEffect.EXPAND,NeovimShortcutEffect.fromPreference("SHUTTER"))
        NeovimShortcutEffect.entries.forEach {assertEquals(it,NeovimShortcutEffect.fromPreference(it.name))}
        assertEquals(NeovimShortcutEffect.FAN_OUT,NeovimShortcutEffect.fromPreference(null))
        assertEquals(NeovimShortcutEffect.FAN_OUT,NeovimShortcutEffect.fromPreference("invalid"))
    }
    @Test fun stationaryAnchorsLetTheWheelOverlapAppBarsWithoutLeavingTheVisibleWindow() {
        val pane=Rect(0f,160f,360f,460f);val window=Rect(0f,24f,360f,640f)
        val range=neovimWheelAnchorRange(pane,window,196f,12f,48f)
        for(at in listOf(range.topLeft,range.bottomRight)) {
            val button=Rect(at+pane.topLeft,androidx.compose.ui.geometry.Size(48f,48f))
            val wheel=Rect(button.center,98f)
            assertTrue(button.left>=pane.left && button.top>=pane.top && button.right<=pane.right && button.bottom<=pane.bottom)
            assertTrue(wheel.left>=window.left && wheel.top>=window.top && wheel.right<=window.right && wheel.bottom<=window.bottom)
        }
        assertTrue(Rect(range.topLeft+pane.topLeft+Offset(24f,24f),98f).top<pane.top)
        assertTrue(Rect(range.bottomRight+pane.topLeft+Offset(24f,24f),98f).bottom>pane.bottom)
        val aboveKeyboard=Rect(0f,24f,360f,400f)
        val keyboardRange=neovimWheelAnchorRange(Rect(0f,160f,360f,350f),aboveKeyboard,196f,12f,48f)
        val bottomCenter=keyboardRange.bottomRight+Offset(24f,184f)
        assertTrue(bottomCenter.y+98f<=aboveKeyboard.bottom)
    }
    @Test fun eightSectorsStayFixedWithANeutralHubAndDistantCancellation() {
        for(index in 0..7) {
            val angle=(-90+45*index)*PI/180
            val point=Offset(100+70*cos(angle).toFloat(),100+70*sin(angle).toFloat())
            assertEquals(index,neovimWheelSector(point,200f))
        }
        assertEquals(-1,neovimWheelSector(Offset(100f,100f),200f,true))
        assertEquals(-1,neovimWheelSector(Offset(100f,60f),200f,true))
        assertEquals(-1,neovimWheelSector(Offset(100f,55f),200f,true)) // The visible hub/ring gap stays neutral.
        assertEquals(-1,neovimWheelSector(Offset(100f,-60f),200f,true))
        assertEquals(-1,neovimWheelSector(Offset(100f,-10f),200f))
        assertEquals(0,neovimWheelSector(Offset(100f,-10f),200f,true))
    }
    @Test fun partialPagesKeepEmptySlotsHarmlessAndWrapInBothDirections() {
        val items=(1..9).map {NeovimShortcut("Action $it",":echo $it")}
        val state=NeovimWheelState(items);state.show()
        state.next(-1)
        assertEquals(1,state.page);assertEquals(listOf(NeovimShortcutEntry.Action(items[8])),state.visible)
        assertNull(state.choose(1));assertEquals(items[8],state.choose(0))
        state.next(1);assertEquals(0,state.page)
        state.highlighted=0;state.next(1);assertEquals(-1,state.highlighted)
        state.close();assertNull(state.choose(0))
    }
    @Test fun groupsRestoreTheRootPageAndCenterClosesOnlyAtRoot() {
        val items=NeovimShortcuts.parse((1..9).joinToString("\n") {"Root $it = :echo $it"}+"\n[Git]\nLazyGit = <Space>lg")
        val state=NeovimWheelState(items);state.show();state.next(1)
        assertNull(state.choose(1));assertEquals("Git",state.group);assertEquals(0,state.page)
        assertEquals(items.last(),state.choose(0));assertTrue(state.open)
        state.center();assertEquals("",state.group);assertEquals(1,state.page);assertTrue(state.open)
        state.center();assertFalse(state.open)
    }
}
