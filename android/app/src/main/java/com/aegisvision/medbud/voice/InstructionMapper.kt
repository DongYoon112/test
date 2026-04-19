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
            full = "Before you help, check around. Look for any danger like fire, traffic, or live wires.",
            short = "Check the area for danger first.",
            minimal = "Danger?",
        ),
        "identify_hazard" to InstructionVariants(
            full = "Find the source of the danger. Point the camera so I can see it.",
            short = "Find the hazard. Show me.",
            minimal = "Where?",
        ),
        "move_to_safety" to InstructionVariants(
            full = "Move yourself and the person to a safer spot away from the danger.",
            short = "Get to a safer spot.",
            minimal = "Move.",
        ),
        "confirm_safe_position" to InstructionVariants(
            full = "Confirm you and the patient are in a safe place. Say 'safe' when you are.",
            short = "Are you both safe now?",
            minimal = "Safe?",
        ),

        // Bleeding -----------------------------------------------------------
        "locate_bleeding_source" to InstructionVariants(
            full = "Show me the wound. Point the camera directly at the bleeding so I can see where it's coming from.",
            short = "Show me the bleeding clearly.",
            minimal = "Where?",
        ),
        "apply_bleeding_control" to InstructionVariants(
            full = "Press firmly on the wound with a clean cloth or your hand. Keep steady pressure and don't lift until I say so.",
            short = "Press firmly on the wound and hold.",
            minimal = "Press hard.",
        ),
        "reassess_bleeding" to InstructionVariants(
            full = "How's the bleeding now? Tell me if it's slowed, stopped, or gotten worse.",
            short = "How's the bleeding now?",
            minimal = "Still bleeding?",
        ),

        // Breathing ----------------------------------------------------------
        "locate_chest" to InstructionVariants(
            full = "Point the camera at the person's chest so I can see if it's rising and falling.",
            short = "Point the camera at the chest.",
            minimal = "Chest.",
        ),
        "confirm_chest_motion" to InstructionVariants(
            full = "Watch the chest for five seconds. Tell me if you see it rising and falling.",
            short = "Is the chest rising and falling?",
            minimal = "Chest moving?",
        ),
        "reassess_breathing" to InstructionVariants(
            full = "Check breathing again. Watch the chest and listen close to their mouth.",
            short = "Check breathing again.",
            minimal = "Breathing?",
        ),

        // Airway -------------------------------------------------------------
        "observe_airway" to InstructionVariants(
            full = "Look at the mouth and nose. Check for anything blocking the airway.",
            short = "Check the mouth and nose for a blockage.",
            minimal = "Airway.",
        ),
        "clear_airway_if_safe" to InstructionVariants(
            full = "If you see something blocking the airway and you can safely remove it, do so now. Tell me when it's clear.",
            short = "Clear anything in the airway, carefully.",
            minimal = "Clear it.",
        ),
        "reassess_airway" to InstructionVariants(
            full = "Check the airway one more time. Is it clear now?",
            short = "Is the airway clear now?",
            minimal = "Clear?",
        ),

        // Responsiveness -----------------------------------------------------
        "observe_patient" to InstructionVariants(
            full = "Look closely at the person. Check their eyes and whether they're moving.",
            short = "Look at them closely.",
            minimal = "Look.",
        ),
        "attempt_verbal_response" to InstructionVariants(
            full = "Speak loudly to them and tap their shoulder. Tell me if they respond in any way.",
            short = "Speak to them loudly. Do they respond?",
            minimal = "Speak.",
        ),
        "reassess_responsiveness" to InstructionVariants(
            full = "Try again to get a response. Any movement, eye opening, or sound?",
            short = "Any response at all?",
            minimal = "Response?",
        ),

        // Monitor ------------------------------------------------------------
        "general_observation" to InstructionVariants(
            full = "Keep watching the person. Tell me right away if anything changes.",
            short = "Keep watching. Tell me if anything changes.",
            minimal = "Watch.",
        ),
        "reassess_state" to InstructionVariants(
            full = "Stay watching. I'll speak up if I see anything new.",
            short = "Stay watching.",
            minimal = "Watch.",
        ),

        // Locate patient -----------------------------------------------------
        "scan_surroundings" to InstructionVariants(
            full = "Look around slowly. Point the camera at whoever needs help.",
            short = "Point the camera at the person.",
            minimal = "Find.",
        ),
        "confirm_patient_visible" to InstructionVariants(
            full = "Keep the person in the center of the frame so I can see them clearly.",
            short = "Keep them in view.",
            minimal = "Hold.",
        ),

        // Wait ---------------------------------------------------------------
        "continue_observation" to InstructionVariants(
            full = "Hold the camera steady. I need a few more seconds to see clearly.",
            short = "Hold steady. Let me see.",
            minimal = "Hold.",
        ),
        "gather_more_frames" to InstructionVariants(
            full = "Hold the camera steady on what you want me to see.",
            short = "Hold steady.",
            minimal = "Hold.",
        ),

        // Handoff ------------------------------------------------------------
        "handoff_to_external" to InstructionVariants(
            full = "Call 911 now. Put the phone on speaker so I can still help while you talk.",
            short = "Call 911 now.",
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
