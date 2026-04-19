package com.aegisvision.medbud.report

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text renderer for a [HandoffReport]. One public entry point so
 * the UI copy / share path, any future export, and debug screens all
 * produce identical output.
 */
object HandoffReportText {

    private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun render(report: HandoffReport): String {
        val sb = StringBuilder()
        sb.append("AEGIS VISION — INCIDENT REPORT\n")
        sb.append("==============================\n")
        sb.append("ID:       ").append(report.incidentId).append('\n')
        sb.append("Started:  ").append(CLOCK.format(Date((report.startTimeSec * 1000).toLong()))).append('\n')
        sb.append("Ended:    ").append(CLOCK.format(Date((report.endTimeSec * 1000).toLong()))).append('\n')
        sb.append("Duration: ").append(formatDuration(report.durationSec)).append('\n')
        sb.append('\n')

        sb.append("BRIEF\n").append(report.briefText).append("\n\n")

        sb.append("SCENE\n  ").append(report.sceneSummary).append("\n\n")
        sb.append("PATIENT\n  ").append(report.patientSummary).append("\n\n")

        section(sb, "PRIMARY CONCERNS", report.primaryConcerns, empty = "  None.")
        section(sb, "ACTIONS PERFORMED", report.actionsPerformed, empty = "  No guided steps completed.")
        section(sb, "UNRESOLVED", report.unresolvedConcerns, empty = "  None noted.")
        section(sb, "CONFIDENCE NOTES", report.confidenceNotes, empty = "  None.")
        section(sb, "TIMELINE", report.timelineSummary, empty = "  (empty)")

        return sb.toString().trimEnd()
    }

    private fun section(sb: StringBuilder, title: String, lines: List<String>, empty: String) {
        sb.append(title).append('\n')
        if (lines.isEmpty()) {
            sb.append(empty).append('\n')
        } else {
            for (line in lines) sb.append("  • ").append(line).append('\n')
        }
        sb.append('\n')
    }

    private fun formatDuration(sec: Int): String {
        val m = sec / 60
        val r = sec % 60
        return if (m > 0) "%d min %02d sec".format(m, r) else "%d sec".format(r)
    }
}
