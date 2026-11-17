package dev.lemon.rex

import org.json.JSONObject

/** Visible intersection of a server-reported pane with its window, in window fractions. */
data class PanePlacement(val id: String,val x: Float,val y: Float,val width: Float,val height: Float,val layer: Int=0,val floating: Boolean=false) {
    companion object {
        fun parse(value: JSONObject?): PanePlacement? {
            value ?: return null
            val id=value.optString("id").takeIf {it.isNotBlank()} ?: return null
            val rect=value.optJSONArray("rect")?.takeIf {it.length()==4} ?: return null
            val numbers=(0..3).map {rect.optDouble(it,Double.NaN)}
            if(numbers.any {!it.isFinite()} || numbers[2]<=0 || numbers[3]<=0) return null
            val right=numbers[0]+numbers[2];val bottom=numbers[1]+numbers[3]
            if(!right.isFinite() || !bottom.isFinite()) return null
            val x=numbers[0].coerceIn(0.0,1.0);val y=numbers[1].coerceIn(0.0,1.0)
            val w=right.coerceIn(0.0,1.0)-x;val h=bottom.coerceIn(0.0,1.0)-y
            if(w<=0 || h<=0) return null
            val width=w.toFloat();val height=h.toFloat()
            if(width<=0 || height<=0) return null
            return PanePlacement(id,x.toFloat(),y.toFloat(),width,height,value.optInt("layer").coerceIn(0,255),value.optBoolean("floating"))
        }
    }
}
