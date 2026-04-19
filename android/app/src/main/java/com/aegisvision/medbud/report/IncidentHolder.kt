package com.aegisvision.medbud.report

import com.aegisvision.medbud.action.ActionPlanState
import com.aegisvision.medbud.clarification.ClarificationState
import com.aegisvision.medbud.decision.DecisionState
import com.aegisvision.medbud.guidance.GuidanceLoopEngine
import com.aegisvision.medbud.guidance.GuidanceLoopState
import com.aegisvision.medbud.perception.PerceptionRepository
import com.aegisvision.medbud.perception.PerceptionState

/**
 * Process-scoped wiring for Phase 6. Mirrors [PerceptionHolder] /
 * [VoiceHolder]. Call [ensureStarted] once — subsequent calls are
 * idempotent.
 */
object IncidentHolder {

    @Volatile private var _logger: IncidentLogger? = null
    @Volatile private var _bridge: IncidentBridge? = null

    val logger: IncidentLogger? get() = _logger

    fun ensureStarted(
        repo: PerceptionRepository,
        guidance: GuidanceLoopEngine,
    ) {
        if (_logger != null) return
        synchronized(this) {
            if (_logger != null) return
            val logger = IncidentLogger()
            val bridge = IncidentBridge(logger, repo, guidance)
            _logger = logger
            _bridge = bridge
        }
    }

    /** Begin a new incident session and start collecting events. */
    fun beginIncident() {
        val l = _logger ?: return
        val b = _bridge ?: return
        l.startSession()
        b.start()
    }

    /**
     * Finalize the active incident. Generates a [HandoffReport] from the
     * current pipeline snapshots and stashes it on the session. Safe to
     * call multiple times; only the first call on an ACTIVE session
     * produces a report.
     */
    fun endIncident(
        status: IncidentStatus = IncidentStatus.ENDED,
        perception: PerceptionState,
        decision: DecisionState,
        plan: ActionPlanState,
        clarification: ClarificationState,
        guidance: GuidanceLoopState,
        note: String = "",
    ): IncidentSession? {
        val l = _logger ?: return null
        val b = _bridge
        val activeSession = l.current
        if (activeSession == null || activeSession.status != IncidentStatus.ACTIVE) {
            return activeSession
        }
        // Generate the report *before* marking the session ended so the
        // timeline still contains every pre-end event.
        val preview = activeSession.copy(endTimeSec = com.aegisvision.medbud.perception.nowSec())
        val report = ReportGenerator.generate(preview, perception, decision, plan, guidance)
        val ended = l.endSession(status = status, report = report, note = note)
        b?.stop()
        // clarification param is reserved for future use (e.g. unresolved
        // clarification text in the report) — referenced so callers don't
        // have to conditionally pass it.
        @Suppress("UNUSED_VARIABLE") val clar = clarification
        return ended
    }

    /** Clear the logger after the user reviews the report. */
    fun reset() {
        _logger?.reset()
    }
}
