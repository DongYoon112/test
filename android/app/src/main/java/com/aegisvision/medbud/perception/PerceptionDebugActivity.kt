package com.aegisvision.medbud.perception

import android.os.Bundle
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aegisvision.medbud.R
import com.aegisvision.medbud.action.ActionPlanState
import com.aegisvision.medbud.clarification.ClarificationState
import com.aegisvision.medbud.decision.DecisionState
import com.aegisvision.medbud.guidance.AdaptationState
import com.aegisvision.medbud.guidance.GuidanceLoopState
import com.aegisvision.medbud.voice.VoiceHolder
import com.aegisvision.medbud.voice.VoiceInstructionEngine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Read-only debug screen for perception state. Observes [PerceptionViewModel]
 * via lifecycle-aware coroutine; no polling, no leaks.
 */
class PerceptionDebugActivity : AppCompatActivity() {

    private val viewModel: PerceptionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_perception_debug)
        title = "Perception (debug)"

        val summary = findViewById<TextView>(R.id.summaryText)
        val bleeding = findViewById<TextView>(R.id.bleedingText)
        val breathing = findViewById<TextView>(R.id.breathingText)
        val conscious = findViewById<TextView>(R.id.consciousText)
        val person = findViewById<TextView>(R.id.personText)
        val lists = findViewById<TextView>(R.id.listsText)
        val speech = findViewById<TextView>(R.id.speechText)
        val footer = findViewById<TextView>(R.id.footerText)

        val dPrimary = findViewById<TextView>(R.id.decisionPrimaryText)
        val dSecondary = findViewById<TextView>(R.id.decisionSecondaryText)
        val dFocus = findViewById<TextView>(R.id.decisionFocusText)
        val dBlockers = findViewById<TextView>(R.id.decisionBlockersText)
        val dMissing = findViewById<TextView>(R.id.decisionMissingText)
        val dRationale = findViewById<TextView>(R.id.decisionRationaleText)

        val cNeed = findViewById<TextView>(R.id.clarNeedText)
        val cCands = findViewById<TextView>(R.id.clarCandidatesText)
        val cPrompt = findViewById<TextView>(R.id.clarPromptText)
        val cGain = findViewById<TextView>(R.id.clarGainText)
        val cRationale = findViewById<TextView>(R.id.clarRationaleText)

        val aStatus = findViewById<TextView>(R.id.planStatusText)
        val aSecondary = findViewById<TextView>(R.id.planSecondaryText)
        val aBlockers = findViewById<TextView>(R.id.planBlockersText)
        val aSafety = findViewById<TextView>(R.id.planSafetyText)
        val aSteps = findViewById<TextView>(R.id.planStepsText)
        val aRationale = findViewById<TextView>(R.id.planRationaleText)

        val voiceLastSpoken = findViewById<TextView>(R.id.voiceLastSpokenText)
        val gStatus = findViewById<TextView>(R.id.guidanceStatusText)
        val gDetail = findViewById<TextView>(R.id.guidanceDetailText)
        val gRationale = findViewById<TextView>(R.id.guidanceRationaleText)
        val aMode = findViewById<TextView>(R.id.adaptModeText)
        val aMetrics = findViewById<TextView>(R.id.adaptMetricsText)

        // Ensure the voice engine is running before we try to observe its flow.
        VoiceHolder.ensureStarted(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.decisionState.collect { d -> renderDecision(
                        d, dPrimary, dSecondary, dFocus, dBlockers, dMissing, dRationale,
                    ) }
                }
                launch {
                    viewModel.clarificationState.collect { c -> renderClarification(
                        c, cNeed, cCands, cPrompt, cGain, cRationale,
                    ) }
                }
                launch {
                    viewModel.actionPlanState.collect { a -> renderActionPlan(
                        a, aStatus, aSecondary, aBlockers, aSafety, aSteps, aRationale,
                    ) }
                }
                launch {
                    VoiceHolder.engine?.lastSpoken?.collect { line ->
                        renderVoice(line, voiceLastSpoken)
                    }
                }
                launch {
                    VoiceHolder.guidance?.state?.collect { g ->
                        renderGuidance(g, gStatus, gDetail, gRationale)
                    }
                }
                launch {
                    VoiceHolder.guidance?.adaptation?.state?.collect { a ->
                        renderAdaptation(a, aMode, aMetrics)
                    }
                }
                viewModel.state.collect { s ->
                    summary.text = s.summary
                    bleeding.text = formatField("bleeding", s.bleeding)
                    breathing.text = formatField("breathing", s.breathing)
                    conscious.text = formatField("conscious", s.conscious)
                    person.text = formatField("person_visible", s.personVisible)
                    lists.text = buildString {
                        append("body_parts: ")
                        append(if (s.bodyPartsVisible.isEmpty()) "—" else s.bodyPartsVisible.joinToString(", "))
                        append('\n')
                        append("scene_risk: ")
                        append(if (s.sceneRisk.isEmpty()) "—" else s.sceneRisk.joinToString(", "))
                    }
                    speech.text = if (s.recentSpeech.isBlank()) "(none)" else s.recentSpeech
                    footer.text = String.format(
                        Locale.US,
                        "frames=%d  global_conf=%.2f  t=%.1f",
                        s.framesInBuffer, s.globalConfidence, s.timestampSec,
                    )
                }
            }
        }
    }

    private fun renderDecision(
        d: DecisionState,
        primary: TextView,
        secondary: TextView,
        focus: TextView,
        blockers: TextView,
        missing: TextView,
        rationale: TextView,
    ) {
        primary.text = String.format(
            Locale.US,
            "primary:   %-22s urgency=%-8s conf=%.2f",
            d.primaryPriority.name.lowercase(Locale.US),
            d.urgency.name.lowercase(Locale.US),
            d.confidence,
        )
        secondary.text = "secondary: " + if (d.secondaryPriorities.isEmpty()) "—"
            else d.secondaryPriorities.joinToString(", ") { it.name.lowercase(Locale.US) }
        focus.text = "next_focus: " + d.nextFocus
        blockers.text = "blockers:  " + if (d.blockers.isEmpty()) "—" else d.blockers.joinToString("; ")
        missing.text = "missing:   " + if (d.missingInfo.isEmpty()) "—" else d.missingInfo.joinToString("; ")
        rationale.text = d.rationale
    }

    private fun renderClarification(
        c: ClarificationState,
        need: TextView,
        cands: TextView,
        prompt: TextView,
        gain: TextView,
        rationale: TextView,
    ) {
        need.text = String.format(
            Locale.US,
            "need:      %-30s prio=%.2f",
            c.primaryClarificationNeed.type.name.lowercase(Locale.US),
            c.primaryClarificationNeed.priority,
        )
        cands.text = "others:    " + if (c.candidateNeeds.isEmpty()) "—"
            else c.candidateNeeds.joinToString(", ") { it.type.name.lowercase(Locale.US) }
        prompt.text = if (c.recommendedPrompt.mode == com.aegisvision.medbud.clarification.PromptMode.NONE)
            "prompt:    (passive — continue monitoring)"
        else String.format(
            Locale.US,
            "prompt:    [%s] \"%s\"",
            c.recommendedPrompt.mode.name.lowercase(Locale.US),
            c.recommendedPrompt.promptText,
        )
        gain.text = String.format(
            Locale.US,
            "est_gain:  %.2f   tag=%s",
            c.confidenceGainEstimate,
            c.recommendedPrompt.shortLabel,
        )
        rationale.text = c.rationale
    }

    private fun renderActionPlan(
        a: ActionPlanState,
        status: TextView,
        secondary: TextView,
        blockers: TextView,
        safety: TextView,
        steps: TextView,
        rationale: TextView,
    ) {
        status.text = String.format(
            Locale.US,
            "status:    %-20s primary=%-22s readiness=%.2f",
            a.status.name.lowercase(Locale.US),
            a.primaryAction.name.lowercase(Locale.US),
            a.readiness,
        )
        secondary.text = "secondary: " + if (a.secondaryActions.isEmpty()) "—"
            else a.secondaryActions.joinToString(", ") { it.name.lowercase(Locale.US) }
        blockers.text = "blockers:  " + if (a.blockers.isEmpty()) "—" else a.blockers.joinToString("; ")
        safety.text = "safety:    " + if (a.safetyFlags.isEmpty()) "—" else a.safetyFlags.joinToString("; ")
        steps.text = "steps:     " + if (a.plannedSteps.isEmpty()) "—"
            else a.plannedSteps.joinToString(" → ") { it.instructionKey }
        rationale.text = a.rationale
    }

    private fun renderGuidance(
        g: GuidanceLoopState,
        status: TextView,
        detail: TextView,
        rationale: TextView,
    ) {
        status.text = String.format(
            Locale.US,
            "loop:      %-10s esc=%-9s step=%d  retry=%d",
            g.loopStatus.name.lowercase(Locale.US),
            g.escalationLevel.name.lowercase(Locale.US),
            g.currentStepIndex,
            g.retryCount,
        )
        detail.text = String.format(
            Locale.US,
            "lastResp:  %-20s  completion=%s  key=%s",
            g.lastUserResponse.name.lowercase(Locale.US),
            g.completionState.name.lowercase(Locale.US),
            g.currentInstructionKey ?: "—",
        )
        rationale.text = g.rationale
    }

    private fun renderAdaptation(a: AdaptationState, modeView: TextView, metricsView: TextView) {
        modeView.text = String.format(
            Locale.US,
            "adapt:     mode=%-12s level=%-7s pacing=%s",
            a.currentMode.name.lowercase(Locale.US),
            a.instructionComplexity.name.lowercase(Locale.US),
            a.pacing.name.lowercase(Locale.US),
        )
        metricsView.text = String.format(
            Locale.US,
            "metrics:   reliability=%.2f  failureRate=%.2f  speed=%.2f",
            a.userReliabilityScore, a.failureRate, a.responseSpeed,
        )
    }

    private fun renderVoice(line: VoiceInstructionEngine.SpokenLine?, view: TextView) {
        view.text = if (line == null) {
            "(nothing spoken yet)"
        } else {
            String.format(
                Locale.US,
                "[%s] \"%s\"",
                line.urgency.name.lowercase(Locale.US),
                line.text,
            )
        }
    }

    private fun formatField(name: String, f: FieldState): String =
        String.format(
            Locale.US,
            "%-17s value=%-8s conf=%.2f  stab=%s  trend=%s  dur=%.1fs  src=%s  vs=%.2f",
            "$name:",
            f.value,
            f.confidence,
            f.stability.name.lowercase(Locale.US),
            f.trend.name.lowercase(Locale.US),
            f.durationSec,
            f.source.name.lowercase(Locale.US),
            f.visualSupport,
        )
}
