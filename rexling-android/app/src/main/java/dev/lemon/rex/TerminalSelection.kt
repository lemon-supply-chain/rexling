package dev.lemon.rex

/** A frozen viewport selection. Coordinates are terminal cells, never UTF-16 offsets. */
class TerminalSelection(val frame: TerminalScreen, column: Int, row: Int) {
    data class Point(val column: Int,val row: Int): Comparable<Point> {
        override fun compareTo(other: Point): Int = compareValuesBy(this,other,Point::row,Point::column)
    }
    var anchor=point(column,row);private set
    var extent=anchor;private set
    val first: Point get()=minOf(anchor,extent)
    val last: Point get()=maxOf(anchor,extent)
    private fun cell(point: Point)=frame.lines[point.row].firstOrNull {it.width>0 && point.column>=it.x && point.column<it.x+it.width}
    private fun point(column: Int,row: Int): Point {
        val p=Point(column.coerceIn(0,frame.columns-1),row.coerceIn(0,frame.rows-1))
        return Point(cell(p)?.x ?: p.column,p.row)
    }
    fun word() {
        val origin=anchor
        fun whitespace(column: Int)=cell(point(column,origin.row))?.text.orEmpty().ifEmpty {" "}.all(Char::isWhitespace)
        if(whitespace(origin.column)) return
        var left=origin.column;var right=origin.column
        while(left>0 && !whitespace(left-1)) left--
        while(right<frame.columns-1 && !whitespace(right+1)) right++
        anchor=point(left,origin.row);extent=point(right,origin.row)
    }
    fun move(column: Int,row: Int,moveAnchor: Boolean=false) {
        if(moveAnchor) anchor=point(column,row) else extent=point(column,row)
    }
    fun all() {anchor=Point(0,0);extent=point(frame.columns-1,frame.rows-1)}
    fun endColumn(point: Point)=point.column+(cell(point)?.width ?: 1)
    fun text(): String = (first.row..last.row).joinToString("\n") {row->
        val start=if(row==first.row) first.column else 0
        val end=if(row==last.row) endColumn(last)-1 else frame.columns-1
        val result=StringBuilder();var column=start
        for(c in frame.lines[row]) {
            if(c.width==0 || c.x+c.width<=start || c.x>end) continue
            if(c.x>column) result.append(" ".repeat(c.x-column))
            result.append(c.text.ifEmpty {" "});column=c.x+c.width
        }
        if(column<=end) result.append(" ".repeat(end-column+1))
        if(end>=frame.columns-1) result.toString().trimEnd() else result.toString()
    }.trimEnd('\n')
}
