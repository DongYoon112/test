package com.aegisvision.medbud.report

import android.util.Log
import com.aegisvision.medbud.perception.nowSec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-scoped recorder for the incident timeline.
 *
 * Single-writer semantics: only [IncidentBridge] should call [record];
 * UI reads [session] for live updates. The logger owns session lifecycle
 * (start / end / reset) and keeps events in append-only chronological
 * order. Deduplication is **not** done here — the bridge decides what
 * counts as meaningful; the logger just stores what it's given.
 */
class IncidentLogger {

    private val _session = MutableStateFlow<IncidentSession?>(null)
    val session: StateFlow<IncidentSession?> = _session.asStateFlow()

    /** Cached read for synchronous callers (e.g. activity onCreate). */
    val current: IncidentSession? get() = _session.value

    /**
     * Begin a new active session. No-op if a session is already active —
     * the caller must [endSession] first. Also no-op if [session] holds a
     * previously-ended session; calling [reset] clears that slot.
     */
    fun startSession(): IncidentSession {
        val existing = _session.value
        if (existing != null && existing.status == IncidentStatus.ACTIVE) {
            Log.i(TAG, "startSession() ignored — active session already present")
            return existing
        }
        val fresh = IncidentSession.newActive()
        _session.value = fresh
        Log.i(TAG, "session start: ${fresh.incidentId}")
        record(TimelineEvent(
            timestampSec = nowSec(),
            type = TimelineEventType.SESSION_START,
            title = "Session started",
            details = "",
            source = EventSource.SYSTEM_INFERRED,
        ))
        return _session.value!!
    }

    /**
     * Append a timeline event to the active session. No-op if no session
     * is active. Never blocks — UI observes changes via [session].
     */
    fun record(event: TimelineEvent) {
        val s = _session.value ?: return
        if (s.status != IncidentStatus.ACTIVE) return
        _session.value = s.copy(timelineEvents = s.timelineEvents + event)
    }

    /**
     * Finalize the active session. Any caller that has a generated
     * [HandoffReport] passes it here so UI can display it without
     * recomputing. Leaving [report] null marks the session ended but
     * un-reported — useful when the app crashed or the user bailed.
     */
    fun endSession(
        status: IncidentStatus = IncidentStatus.ENDED,
        report: HandoffReport? = null,
        note: String = "",
    ): IncidentSession? {
        val s = _session.value ?: return null
        if (s.status != IncidentStatus.ACTIVE) return s
        val end = nowSec()
        Log.i(TAG, "session end: ${s.incidentId} status=${status.name}")
        // Record the terminator *before* flipping the status so the
        // SESSION_END event itself lives inside the session.
        record(TimelineEvent(
            timestampSec = end,
            type = TimelineEventType.SESSION_END,
            title = "Session ended",
            details = "${status.name.lowercase()}${if (note.isNotEmpty()) " — $note" else ""}",
            source = EventSource.SYSTEM_INFERRED,
        ))
        val finished = _session.value!!.copy(
            endTimeSec = end,
            status = status,
            finalReport = report,
        )
        _session.value = finished
        return finished
    }

    /**
     * Wipe the logger so the next [startSession] starts cleanly. Keep the
     * report-display path in UI working by inspecting [current] *before*
     * calling reset.
     */
    fun reset() {
        Log.i(TAG, "logger reset")
        _session.value = null
    }

    companion object { private const val TAG = "IncidentLogger" }
}
