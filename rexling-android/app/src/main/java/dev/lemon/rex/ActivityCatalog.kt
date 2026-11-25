package dev.lemon.rex

import android.content.Context
import androidx.annotation.StringRes
import java.time.Instant
import org.json.JSONObject

enum class ActivityState(@StringRes val labelRes: Int, val wire: String, val priority: Int) {
    BLOCKED(R.string.filter_attention, "blocked", 0),
    ERROR(R.string.a11y_state_failed, "error", 1),
    DONE(R.string.filter_finished, "done", 3),
    WORKING(R.string.a11y_state_working, "working", 2),
    IDLE(R.string.a11y_state_idle, "idle", 4),
}

internal val ActivityState.notifiable: Boolean
    get() =
        this == ActivityState.BLOCKED || this == ActivityState.ERROR || this == ActivityState.DONE

data class ActivityRecord(
    val session: String,
    val sessionLabel: String,
    val window: String,
    val windowLabel: String,
    val block: String,
    val blockLabel: String,
    val id: String,
    val title: String,
    val message: String,
    val state: ActivityState,
    val kind: String,
    val progress: Int?,
    val updated: String,
    val program: ProgramIdentity,
    val attentionKey: String = "",
    val attentionVersion: String = "",
    val codexTitleEligible: Boolean = false,
    val codexTitleAttention: Boolean = false,
    val terminalProgress: TerminalProgress? = null,
    val progressOnly: Boolean = false,
) {
    val notifiable: Boolean
        get() = state.notifiable && !progressOnly

    val key: String = listOf(session, block, id).joinToString("") { "${it.length}:$it" }
    val label: String
        get() = program.paneTitle(blockLabel, title)

    val context: String
        get() =
            listOf(
                    sessionLabel,
                    windowLabel.takeUnless { generatedWindow.matches(it) }.orEmpty(),
                    if (progressOnly) "" else id,
                )
                .filter { it.isNotBlank() }
                .joinToString(" · ")

    val timestamp: Long = runCatching { Instant.parse(updated).toEpochMilli() }.getOrDefault(0)
    @get:StringRes
    val stateLabelRes: Int
        get() =
            if (state == ActivityState.BLOCKED)
                when (kind) {
                    "permission" -> R.string.activity_kind_approval
                    "question" -> R.string.activity_kind_question
                    "auth" -> R.string.activity_kind_sign_in
                    else -> state.labelRes
                }
            else state.labelRes

    /** State text for the sheet and notifications; progress-only rows keep their model label. */
    fun stateLabel(context: Context): String =
        if (progressOnly && terminalProgress != null) terminalProgress.label
        else context.getString(stateLabelRes)

    companion object {
        private val generatedWindow = Regex("(?i)window(?:[ \t_-]+)[0-9a-f]{6,}(?:-[0-9a-f]+)*")

        private fun JSONObject.clean(name: String, max: Int) =
            optString(name)
                .filterNot {
                    it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
                }
                .take(max)

        fun parse(value: JSONObject): ActivityRecord? {
            val session = value.clean("session", 256)
            val block = value.clean("block", 256)
            val state =
                ActivityState.entries.firstOrNull { it.wire == value.optString("state") }
                    ?: return null
            if (session.isBlank() || block.isBlank()) return null
            val progress =
                if (
                    value.isNull("progress") ||
                        (!value.optBoolean("progressOnly") &&
                            state !in listOf(ActivityState.WORKING, ActivityState.BLOCKED))
                )
                    null
                else value.optInt("progress", -1).takeIf { it in 0..100 }
            return ActivityRecord(
                session,
                value.clean("sessionLabel", 256),
                value.clean("window", 256),
                value.clean("windowLabel", 256),
                block,
                value.clean("blockLabel", 256),
                value.clean("id", 128),
                value.clean("title", 256),
                value.clean("message", 2048),
                state,
                value.clean("kind", 32),
                progress,
                value.clean("updatedAt", 64),
                ProgramIdentity.resolve(listOf(value.clean("app", 64), value.clean("owner", 64))),
                value.clean("attentionKey", 1024),
                value.clean("attentionVersion", 64),
                value.optBoolean("codexTitleEligible"),
                value.optBoolean("codexTitleAttention"),
                TerminalProgress.parse(value.optJSONObject("terminalProgress")),
                value.optBoolean("progressOnly"),
            )
        }
    }
}

internal data class ActivitySource(
    val server: ServerProfile,
    val connected: Boolean,
    val loading: Boolean,
    val error: String,
    val partial: Boolean,
    val items: List<ActivityRecord>,
    val seen: Set<String> = emptySet(),
)

internal data class ActivityTarget(val server: ServerProfile, val record: ActivityRecord)

internal enum class ActivityFilter {
    ALL,
    ATTENTION,
    WORKING,
    FINISHED,
}

internal object ActivityCatalog {
    fun records(source: ActivitySource, filter: ActivityFilter): List<ActivityRecord> =
        source.items
            .filter { record ->
                when (filter) {
                    ActivityFilter.ALL -> true
                    ActivityFilter.ATTENTION ->
                        record.state in listOf(ActivityState.BLOCKED, ActivityState.ERROR)
                    ActivityFilter.WORKING -> record.state == ActivityState.WORKING
                    ActivityFilter.FINISHED -> record.state == ActivityState.DONE
                }
            }
            .distinctBy { it.key }
            .sortedWith(
                compareBy<ActivityRecord> {
                        if (it.key in source.seen) it.state.priority + 5 else it.state.priority
                    }
                    .thenByDescending { it.timestamp }
                    .thenBy { it.key },
            )
}
