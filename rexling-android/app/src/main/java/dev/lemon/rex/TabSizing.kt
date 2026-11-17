package dev.lemon.rex

import kotlin.math.roundToInt
import kotlin.math.abs

internal data class TabWidth(val width: Float,val showTitle: Boolean)
internal const val TAB_GAP_DP=2f

/** Content widths include the icon, text and padding. Allocation uses whole pixels. */
internal fun tabWidths(available: Float,contentWidths: List<Float>,activeIndex: Int,density: Float=1f): List<TabWidth> {
    if(contentWidths.isEmpty()) return emptyList()
    val minimum=(48f*density).roundToInt()
    val count=contentWidths.size
    val space=(available.coerceAtLeast(0f)*density).roundToInt()-(TAB_GAP_DP*density).roundToInt()*(count-1)
    if(count==1) return listOf(TabWidth((contentWidths.single()*density).roundToInt().coerceAtMost(space).coerceAtLeast(minimum)/density,true))
    val preferred=contentWidths.mapIndexed {i,width->(width*density).roundToInt().coerceIn(minimum,((if(i==activeIndex) 160f else 120f)*density).roundToInt())}
    val widths=IntArray(count) {minimum}
    if(activeIndex in widths.indices) {
        val full=preferred[activeIndex]
        val afterIcons=space-minimum*(count-1)
        val leastTitle=minOf(full,(104f*density).roundToInt())
        widths[activeIndex]=if(afterIcons>=leastTitle) minOf(full,afterIcons) else full
    }
    fun distance(i: Int)=if(activeIndex in widths.indices) abs(i-activeIndex) else 0
    val inactive=widths.indices.filter {it!=activeIndex}.sortedBy(::distance)
    fun grow(indices: List<Int>,budget: Int): Int {
        var remaining=budget
        var candidates=indices.filter {widths[it]<preferred[it]}
        while(remaining>0 && candidates.isNotEmpty()) {
            val share=maxOf(1,remaining/candidates.size)
            for(i in candidates) {
                val added=minOf(share,preferred[i]-widths[i],remaining)
                widths[i]+=added;remaining-=added
            }
            candidates=candidates.filter {widths[it]<preferred[it]}
        }
        return remaining
    }
    var spare=(space-widths.sum()).coerceAtLeast(0)
    val labelMinimum=preferred.map {minOf(it,(74f*density).roundToInt())}
    // Reveal useful captions outward from the active tab before spending the
    // rest on longer captions. Equal-distance neighbors share their space.
    for(i in inactive) if(widths[i]<labelMinimum[i] && spare>=labelMinimum[i]-minimum) {
        spare-=labelMinimum[i]-minimum;widths[i]=labelMinimum[i]
    }
    for(group in inactive.groupBy(::distance).values) {
        spare=grow(group.filter {widths[it]>=labelMinimum[it]},spare)
    }
    return widths.indices.map {TabWidth(widths[it]/density,it==activeIndex || widths[it]>=labelMinimum[it])}
}
