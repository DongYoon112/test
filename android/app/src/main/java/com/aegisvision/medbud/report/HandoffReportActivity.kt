package com.aegisvision.medbud.report

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aegisvision.medbud.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Medic-mode handoff screen. Reads the most recent session from
 * [IncidentHolder] and renders its [HandoffReport]. Designed to be read
 * in ten seconds under stress: brief at top, source-attributed lists
 * below, timeline last.
 *
 * Shared-report path uses plain text from [HandoffReportText] so the
 * doctor / medic receiving the share sees exactly what the clinician on
 * the scene saw.
 */
class HandoffReportActivity : AppCompatActivity() {

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_handoff_report)

        val meta = findViewById<TextView>(R.id.metaText)
        val brief = findViewById<TextView>(R.id.briefText)
        val concerns = findViewById<TextView>(R.id.concernsText)
        val actions = findViewById<TextView>(R.id.actionsText)
        val unresolved = findViewById<TextView>(R.id.unresolvedText)
        val notes = findViewById<TextView>(R.id.notesText)
        val timeline = findViewById<TextView>(R.id.timelineText)
        val share = findViewById<Button>(R.id.shareButton)

        // Live-update the screen as the logger's session changes so the
        // same activity can show an in-flight preview if opened mid-
        // incident. Once the session ends with a report, it stays pinned.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                IncidentHolder.logger?.session?.collect { session ->
                    val report = session?.finalReport
                    if (report == null) {
                        meta.text = if (session == null) "No incident recorded yet."
                                    else "Session in progress — report will appear when it ends."
                        brief.text = ""
                        concerns.text = ""
                        actions.text = ""
                        unresolved.text = ""
                        notes.text = ""
                        timeline.text = ""
                        share.isEnabled = false
                        return@collect
                    }
                    meta.text = "${report.incidentId}  •  " +
                        "${clock.format(Date((report.startTimeSec * 1000).toLong()))}" +
                        " → ${clock.format(Date((report.endTimeSec * 1000).toLong()))}" +
                        "  •  ${formatDuration(report.durationSec)}"
                    brief.text = report.briefText
                    concerns.text = bullet(report.primaryConcerns, "None.")
                    actions.text = bullet(report.actionsPerformed, "No guided steps completed.")
                    unresolved.text = bullet(report.unresolvedConcerns, "None noted.")
                    notes.text = bullet(report.confidenceNotes, "None.")
                    timeline.text = bullet(report.timelineSummary, "(empty)")
                    share.isEnabled = true
                    share.setOnClickListener { shareReport(report) }
                }
            }
        }
    }

    private fun bullet(lines: List<String>, empty: String): String =
        if (lines.isEmpty()) empty else lines.joinToString("\n") { "•  $it" }

    private fun formatDuration(sec: Int): String {
        val m = sec / 60
        val r = sec % 60
        return if (m > 0) "%d min %02d sec".format(m, r) else "%d sec".format(r)
    }

    private fun shareReport(report: HandoffReport) {
        val text = HandoffReportText.render(report)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Aegis Vision — ${report.incidentId}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(share, "Share handoff report"))
    }
}
