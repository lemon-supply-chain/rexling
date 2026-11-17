package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class TabSizingTest {
    @Test fun aShortActiveTitleFreesSpaceForBothInactiveLabels() {
        val widths=tabWidths(262f,listOf(74f,120f,120f),0)
        assertEquals(74f,widths[0].width,0f)
        assertTrue(widths.all {it.showTitle})
        assertTrue(widths[1].width>widths[2].width)
        assertEquals(262f,widths.sumOf {it.width.toDouble()}.toFloat()+2*TAB_GAP_DP,0f)
    }
    @Test fun roomyTabsStopAtTheirActualContentWidths() {
        val widths=tabWidths(500f,listOf(74f,80f,90f),0)
        assertEquals(listOf(74f,80f,90f),widths.map {it.width})
        assertTrue(widths.all {it.showTitle})
    }
    @Test fun activeGetsPriorityWhenFourLongTabsNeedToFit() {
        val widths=tabWidths(262f,List(4){200f},0)
        assertEquals(112f,widths[0].width,0f)
        assertTrue(widths[0].showTitle)
        assertTrue(widths.drop(1).all {it==TabWidth(48f,false)})
    }
    @Test fun overflowPreservesTheActiveTitleAndMinimumTouchTargets() {
        val widths=tabWidths(262f,List(6){200f},0)
        assertEquals(TabWidth(160f,true),widths[0])
        assertTrue(widths.drop(1).all {it==TabWidth(48f,false)})
        assertTrue(widths.sumOf {it.width.toDouble()}+5*TAB_GAP_DP>262)
    }
    @Test fun hiddenLabelsDoNotLeaveWideGapsAndRecoveredSpaceShowsAnotherTitle() {
        val widths=tabWidths(262f,listOf(74f,120f,120f,120f),0)
        assertEquals(listOf(TabWidth(74f,true),TabWidth(86f,true),TabWidth(48f,false),TabWidth(48f,false)),widths)
    }
    @Test fun fractionalDensitiesKeepAllTargetsInsideAPackedStrip() {
        for(density in listOf(1.2f,1.5f,2.625f,2.75f,3.5f)) for(count in 2..6) {
            val available=74f+(count-1)*52f+8f
            val widths=tabWidths(available,List(count){if(it==0) 74f else 220f},0,density)
            val used=widths.sumOf {(it.width*density).roundToInt()}+(count-1)*(TAB_GAP_DP*density).roundToInt()
            assertTrue("$density: $used",used<=(available*density).roundToInt())
            widths.forEach {
                assertTrue((it.width*density).roundToInt()>=(48*density).roundToInt())
                if(!it.showTitle) assertEquals((48*density).roundToInt(),(it.width*density).roundToInt())
            }
        }
    }
    @Test fun unselectedAndEmptyStripsDoNotReserveActiveSpace() {
        assertEquals(listOf(TabWidth(74f,true),TabWidth(80f,true)),tabWidths(300f,listOf(74f,80f),-1))
        assertTrue(tabWidths(300f,emptyList(),-1).isEmpty())
    }
    @Test fun aSingleTabFitsItsTitleUpToTheAvailableSpaceAndKeepsATouchTarget() {
        assertEquals(TabWidth(74f,true),tabWidths(500f,listOf(74f),0).single())
        assertEquals(TabWidth(300f,true),tabWidths(500f,listOf(300f),0).single())
        assertEquals(TabWidth(262f,true),tabWidths(262f,listOf(300f),0).single())
        assertEquals(TabWidth(48f,true),tabWidths(20f,listOf(20f),0).single())
    }
    @Test fun adjacentCaptionsWinAtEitherEndOfTheStrip() {
        val right=tabWidths(296f,List(3){220f},2)
        assertEquals(listOf(TabWidth(48f,false),TabWidth(84f,true),TabWidth(160f,true)),right)
        assertEquals(right.reversed(),tabWidths(296f,List(3){220f},0))
    }
    @Test fun equallyCloseNeighborsShareSpaceBeforeDistantTabs() {
        assertEquals(listOf(TabWidth(48f,false),TabWidth(78f,true),TabWidth(160f,true),TabWidth(78f,true),TabWidth(48f,false)),
            tabWidths(420f,List(5){220f},2))
    }
    @Test fun aShortDistantCaptionCannotStealAnAdjacentCaptionsBudget() {
        assertEquals(listOf(TabWidth(48f,false),TabWidth(80f,true),TabWidth(160f,true)),tabWidths(292f,listOf(58f,120f,220f),2))
    }
}
