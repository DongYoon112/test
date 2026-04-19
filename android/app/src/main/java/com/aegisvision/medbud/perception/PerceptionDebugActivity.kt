package com.aegisvision.medbud.perception

import android.os.Bundle
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aegisvision.medbud.R
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
