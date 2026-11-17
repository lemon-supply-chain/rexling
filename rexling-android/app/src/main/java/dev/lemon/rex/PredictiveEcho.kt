package dev.lemon.rex

import androidx.annotation.StringRes

/** Local echo of typed text before the server answers; the engine draws and checks the guesses. */
internal enum class PredictiveEcho(
    val wire: String,
    val label: String,
    val detail: String,
    @get:StringRes val labelRes: Int,
    @get:StringRes val detailRes: Int
) {
    OFF("off","Off","Typed text appears when the server echoes it.", R.string.predictive_echo_off, R.string.predictive_echo_off_detail),
    SLOW("slow","When slow","Shows typing immediately once the connection is slow, in shells and pi.", R.string.predictive_echo_slow, R.string.predictive_echo_slow_detail),
    ALWAYS("always","Always","Always shows typing immediately in shells and pi.", R.string.predictive_echo_always, R.string.predictive_echo_always_detail);
    companion object {
        /** Cells the engine predicted and the server has not confirmed yet; drawn underlined. */
        const val PREDICTED_CELL=1 shl 30
        fun fromPreference(value: String?)=entries.firstOrNull {it.name==value} ?: SLOW
    }
}
