package com.aegisvision.medbud.guidance

/**
 * Rule-based user-response parser.
 *
 * Maps an STT transcript to a single [UserResponseType]. Specific patterns
 * are checked **before** generic ones — e.g. "not breathing" must match
 * `BREATHING_ABSENT`, not `NO`.
 *
 * Intentionally regex-based, not LLM-based. Latency + predictability
 * matter more than nuance at this layer.
 */
object ResponseInterpreter {

    private val I = setOf(RegexOption.IGNORE_CASE)

    // Order is significant — the first rule to match wins.
    private val RULES: List<Pair<Regex, UserResponseType>> = listOf(
        // ---------- specific medical signals first -------------------------
        Regex("""\b(not\s+breathing|no\s+breath|isn'?t\s+breathing|stopped\s+breathing|no\s+breathing|chest\s+(isn'?t|not)\s+moving)\b""", I)
            to UserResponseType.BREATHING_ABSENT,
        Regex("""\b(he'?s\s+breathing|she'?s\s+breathing|they'?re\s+breathing|still\s+breathing|breathing\s+(normally|fine|okay|ok)|chest\s+is\s+moving|chest\s+moving)\b""", I)
            to UserResponseType.BREATHING_PRESENT,

        Regex("""\b(still\s+bleeding|more\s+blood|bleeding\s+(worse|worsening|more|heavily|a\s+lot)|lots?\s+of\s+blood|soaked|can'?t\s+stop\s+the\s+bleeding)\b""", I)
            to UserResponseType.BLEEDING_WORSE,
        Regex("""\b(bleeding\s+(stopped|slowed|less|under\s+control)|less\s+blood|stopped\s+bleeding|not\s+bleeding\s+anymore|slowing\s+down)\b""", I)
            to UserResponseType.BLEEDING_BETTER,

        // ---------- "no response" medical meaning --------------------------
        Regex("""\b(no\s+response|they'?re\s+not\s+responding|he'?s\s+not\s+responding|she'?s\s+not\s+responding|unresponsive)\b""", I)
            to UserResponseType.BREATHING_ABSENT.let { UserResponseType.CANT_DO },
        // ^ placeholder: we want a dedicated RESPONSIVENESS_ABSENT category if the
        //   loop starts acting on it; for now it maps to CANT_DO so the loop
        //   skips "attempt_verbal_response" without misfiling as breathing.

        // ---------- explicit "can't do this" -------------------------------
        Regex("""\b(i\s+can'?t|cannot|won'?t\s+work|not\s+possible|no\s+way|doesn'?t\s+work|unable|can'?t\s+do\s+(it|that)|giving\s+up)\b""", I)
            to UserResponseType.CANT_DO,

        // ---------- help request -------------------------------------------
        Regex("""\b(help(\s+me)?|i\s+need\s+help|send\s+help|what\s+do\s+i\s+do|what\s+now|call\s+(someone|911|emergency))\b""", I)
            to UserResponseType.HELP_REQUEST,

        // ---------- hesitation ---------------------------------------------
        Regex("""\b(don'?t\s+know|not\s+sure|unsure|no\s+idea|i\s+have\s+no\s+idea)\b""", I)
            to UserResponseType.DONT_KNOW,

        // ---------- completion ---------------------------------------------
        Regex("""\b(done|finished|complete(d)?|i\s+did\s+it|i'?ve\s+done\s+(it|that)|did\s+(it|that)|got\s+it\s+done|ready)\b""", I)
            to UserResponseType.DONE,

        // ---------- generic yes / no (kept last to avoid stealing above) ----
        Regex("""\b(yes|yeah|yep|yup|okay|ok|sure|alright|right|copy|affirmative|uh[- ]huh|mhm)\b""", I)
            to UserResponseType.YES,
        Regex("""\b(no|nope|nah|negative|negatory)\b""", I)
            to UserResponseType.NO,
    )

    fun parse(transcript: String): UserResponseType {
        val text = transcript.trim()
        if (text.isEmpty()) return UserResponseType.NO_RESPONSE
        for ((pattern, type) in RULES) {
            if (pattern.containsMatchIn(text)) return type
        }
        return UserResponseType.UNCLEAR
    }
}
