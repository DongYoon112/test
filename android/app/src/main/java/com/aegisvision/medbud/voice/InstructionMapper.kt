package com.aegisvision.medbud.voice

/**
 * Verbosity level for spoken instructions. Chosen by the Phase 3.3
 * adaptation engine; used by [InstructionMapper.phraseFor].
 *
 *   FULL    — normal spoken sentence
 *   SHORT   — trimmed phrase, no filler words
 *   MINIMAL — 1–3 words, for CRITICAL_OVERRIDE
 */
enum class InstructionLevel { FULL, SHORT, MINIMAL }

/** Three verbosity variants for a single [instructionKey]. */
data class InstructionVariants(
    val full: String,
    val short: String,
    val minimal: String,
)

/**
 * Maps stable `instructionKey` → three-verbosity phrases.
 *
 * Rules for each variant:
 *   * FULL     — one clear sentence, terminal punctuation, no jargon
 *   * SHORT    — same meaning, drop articles and softeners
 *   * MINIMAL  — 1–3 words; a terse command or question
 *
 * Missing keys fall back to `"Stand by."` across all levels so the loop
 * never crashes on a new planner step.
 */
object InstructionMapper {

    private val VARIANTS: Map<String, InstructionVariants> = mapOf(
        // Scene safety -------------------------------------------------------
        "assess_scene" to InstructionVariants(
            full = "Look around for danger.",
            short = "Check for danger.",
            minimal = "Danger?",
        ),
        "identify_hazard" to InstructionVariants(
            full = "Find the source of danger.",
            short = "Find the hazard.",
            minimal = "Where?",
        ),
        "move_to_safety" to InstructionVariants(
            full = "Move to a safer spot.",
            short = "Move to safety.",
            minimal = "Move.",
        ),
        "confirm_safe_position" to InstructionVariants(
            full = "Confirm you are safe.",
            short = "Safe now?",
            minimal = "Safe?",
        ),

        // Bleeding -----------------------------------------------------------
        "locate_bleeding_source" to InstructionVariants(
            full = "Show me where the bleeding is.",
            short = "Show the bleeding.",
            minimal = "Where?",
        ),
        "apply_bleeding_control" to InstructionVariants(
            full = "Apply firm pressure to the bleeding.",
            short = "Apply pressure to the wound.",
            minimal = "Pressure now.",
        ),
        "reassess_bleeding" to InstructionVariants(
            full = "Check the bleeding again.",
            short = "Check bleeding.",
            minimal = "Still bleeding?",
        ),

        // Breathing ----------------------------------------------------------
        "locate_chest" to InstructionVariants(
            full = "Point the camera at the chest.",
            short = "Camera on chest.",
            minimal = "Chest.",
        ),
        "confirm_chest_motion" to InstructionVariants(
            full = "Check if the chest is moving.",
            short = "Is the chest moving?",
            minimal = "Chest moving?",
        ),
        "reassess_breathing" to InstructionVariants(
            full = "Check breathing again.",
            short = "Check breathing.",
            minimal = "Breathing?",
        ),

        // Airway -------------------------------------------------------------
        "observe_airway" to InstructionVariants(
            full = "Look at the mouth and nose.",
            short = "Check the airway.",
            minimal = "Airway.",
        ),
        "clear_airway_if_safe" to InstructionVariants(
            full = "Clear the airway if you can.",
            short = "Clear the airway.",
            minimal = "Clear it.",
        ),
        "reassess_airway" to InstructionVariants(
            full = "Check the airway again.",
            short = "Recheck airway.",
            minimal = "Clear?",
        ),

        // Responsiveness -----------------------------------------------------
        "observe_patient" to InstructionVariants(
            full = "Look at the person closely.",
            short = "Look at them.",
            minimal = "Look.",
        ),
        "attempt_verbal_response" to InstructionVariants(
            full = "Speak to them. See if they respond.",
            short = "Speak to them.",
            minimal = "Speak.",
        ),
        "reassess_responsiveness" to InstructionVariants(
            full = "Check again if they respond.",
            short = "Any response?",
            minimal = "Response?",
        ),

        // Monitor ------------------------------------------------------------
        "general_observation" to InstructionVariants(
            full = "Keep watching the person.",
            short = "Keep watching.",
            minimal = "Watch.",
        ),
        "reassess_state" to InstructionVariants(
            full = "Keep watching.",
            short = "Watch.",
            minimal = "Watch.",
        ),

        // Locate patient -----------------------------------------------------
        "scan_surroundings" to InstructionVariants(
            full = "Look around for the person.",
            short = "Find them.",
            minimal = "Find.",
        ),
        "confirm_patient_visible" to InstructionVariants(
            full = "Keep the person in view.",
            short = "Keep them in view.",
            minimal = "Hold.",
        ),

        // Wait ---------------------------------------------------------------
        "continue_observation" to InstructionVariants(
            full = "Hold steady. Keep looking.",
            short = "Hold steady.",
            minimal = "Hold.",
        ),
        "gather_more_frames" to InstructionVariants(
            full = "Hold steady.",
            short = "Hold.",
            minimal = "Hold.",
        ),

        // Handoff ------------------------------------------------------------
        "handoff_to_external" to InstructionVariants(
            full = "Call emergency services now.",
            short = "Call 911.",
            minimal = "Call 911.",
        ),
    )

    fun phraseFor(
        instructionKey: String,
        level: InstructionLevel = InstructionLevel.FULL,
    ): String {
        val v = VARIANTS[instructionKey] ?: return "Stand by."
        return when (level) {
            InstructionLevel.FULL -> v.full
            InstructionLevel.SHORT -> v.short
            InstructionLevel.MINIMAL -> v.minimal
        }
    }

    fun contains(instructionKey: String): Boolean = instructionKey in VARIANTS
}
