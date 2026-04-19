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

    // Order is significant — the first rule to match wins. Lean toward
    // accepting user speech as a positive signal wherever plausible, so a
    // demo flow doesn't stall on STT noise.
    private val RULES: List<Pair<Regex, UserResponseType>> = listOf(
        // ---------- BLEEDING (specific first) ------------------------------
        Regex("""\b(still\s+bleeding|keeps?\s+bleeding|won'?t\s+stop|more\s+blood|bleeding\s+(is\s+)?(worse|worsening|more|heavily|a\s+lot|everywhere)|lots?\s+of\s+blood|soaked|can'?t\s+stop\s+the\s+bleeding)\b""", I)
            to UserResponseType.BLEEDING_WORSE,
        Regex("""\b(bleeding\s+(is\s+)?(stopped|slowed|slowing|less|under\s+control|better)|is\s+(slowing|stopping|stopped|slowed)(\s+now)?|less\s+blood|stopped\s+bleeding|not\s+bleeding\s+anymore|slowing\s+down|it\s+stopped|it'?s\s+stopped|it'?s\s+stopping|it'?s\s+slowing|less\s+now|not\s+as\s+much|reduced)\b""", I)
            to UserResponseType.BLEEDING_BETTER,

        // ---------- BREATHING ----------------------------------------------
        Regex("""\b(not\s+breathing|no\s+breath|isn'?t\s+breathing|stopped\s+breathing|no\s+breathing|chest\s+(isn'?t|not)\s+moving|not\s+moving)\b""", I)
            to UserResponseType.BREATHING_ABSENT,
        Regex("""\b(he'?s\s+breathing|she'?s\s+breathing|they'?re\s+breathing|still\s+breathing|breathing\s+(normally|fine|okay|ok|now)|chest\s+is\s+moving|chest\s+moving|chest\s+going\s+up)\b""", I)
            to UserResponseType.BREATHING_PRESENT,

        // ---------- RESPONSIVENESS -----------------------------------------
        // "not responding" is a patient-state report, not a user inability.
        // It should advance/close the plan (handoff), not escalate.
        Regex("""\b(no\s+response|not\s+responding|they'?re\s+not\s+responding|he'?s\s+not\s+responding|she'?s\s+not\s+responding|unresponsive|out\s+cold|passed\s+out|knocked\s+out|won'?t\s+wake\s+up|doesn'?t\s+respond|didn'?t\s+respond|no\s+reaction)\b""", I)
            to UserResponseType.UNRESPONSIVE_CONFIRMED,
        Regex("""\b(they'?re\s+awake|he'?s\s+awake|she'?s\s+awake|they'?re\s+responding|he'?s\s+responding|she'?s\s+responding|they\s+responded|he\s+responded|she\s+responded|they'?re\s+talking|he'?s\s+talking|she'?s\s+talking|opened\s+(his|her|their)\s+eyes|awake\s+now|alert|responsive\s+now|they\s+moved|he\s+moved|she\s+moved)\b""", I)
            to UserResponseType.RESPONSIVE_CONFIRMED,

        // ---------- DONE / ACTION-IN-PROGRESS ------------------------------
        // Treat action-in-progress statements ("I'm applying pressure",
        // "pressing on it", "holding") as equivalent to DONE for this step,
        // so the loop can progress instead of nagging "apply pressure".
        Regex("""\b(applying\s+pressure|apply(ing)?\s+pressure|putting\s+pressure|press(ing)?\s+(on|down)?\s*it|press(ing)?\s+(on|down)|holding\s+(it|pressure|the\s+wound)|i'?m\s+(pressing|pressuring|holding)|pressed\s+on\s+it|got\s+pressure\s+on\s+it|covering\s+the\s+wound)\b""", I)
            to UserResponseType.DONE,
        Regex("""\b(done|finished|complete(d)?|i\s+did\s+it|i'?ve\s+done\s+(it|that)|did\s+(it|that)|got\s+it(\s+done)?|ready|i'?m\s+done|i'?m\s+good|all\s+good)\b""", I)
            to UserResponseType.DONE,

        // ---------- CAN'T ---------------------------------------------------
        Regex("""\b(i\s+can'?t|cannot|won'?t\s+work|not\s+possible|no\s+way|doesn'?t\s+work|unable|can'?t\s+do\s+(it|that)|giving\s+up|too\s+(hard|much))\b""", I)
            to UserResponseType.CANT_DO,

        // ---------- HELP ----------------------------------------------------
        Regex("""\b(help(\s+me)?|i\s+need\s+help|send\s+help|what\s+do\s+i\s+do|what\s+now|call\s+(someone|911|nine[- ]one[- ]one|emergency|ambulance|paramedics))\b""", I)
            to UserResponseType.HELP_REQUEST,

        // ---------- HESITATION ---------------------------------------------
        Regex("""\b(don'?t\s+know|not\s+sure|unsure|no\s+idea|i\s+have\s+no\s+idea)\b""", I)
            to UserResponseType.DONT_KNOW,

        // ---------- YES / NO (kept last) -----------------------------------
        Regex("""\b(yes|yeah|yep|yup|okay|ok|sure|alright|right|copy|affirmative|uh[- ]huh|mhm|mm[- ]?hm)\b""", I)
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
