"""Speech → observation fusion.

Simple keyword-based heuristic. The wearer's utterances in the recent
window bump the vote for specific observation labels. Kept intentionally
narrow — we don't want speech to override what the camera sees, only
tip close decisions.

Rationale: a voice saying "he's not breathing" is a strong hint the VLM
should trust, but the VLM sees skin and motion and clothing that speech
cannot. So speech weights are moderate, not overriding.
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass
from typing import Iterable, List, Tuple

# (compiled pattern, field, label, weight)
_RULES: List[Tuple[re.Pattern, str, str, float]] = [
    # Breathing
    (re.compile(r"\b(not\s+breathing|no\s+breath|stopped\s+breathing)\b", re.I),
     "breathing", "none", 2.0),
    (re.compile(r"\b(can'?t\s+breathe|choking|gasping|wheezing)\b", re.I),
     "breathing", "abnormal", 1.5),
    (re.compile(r"\b(breathing\s+normally|breathing\s+fine)\b", re.I),
     "breathing", "normal", 1.0),

    # Consciousness
    (re.compile(r"\b(unconscious|passed\s+out|knocked\s+out|out\s+cold|unresponsive)\b", re.I),
     "conscious", "no", 2.0),
    (re.compile(r"\b(he'?s\s+awake|she'?s\s+awake|responsive|alert|talking)\b", re.I),
     "conscious", "yes", 1.5),

    # Bleeding
    (re.compile(r"\b(bleeding\s+(heavily|badly|a\s+lot)|lots?\s+of\s+blood|pouring\s+blood|hemorrhag)\b", re.I),
     "bleeding", "heavy", 2.0),
    (re.compile(r"\b(a\s+little\s+blood|small\s+cut|minor\s+bleed)\b", re.I),
     "bleeding", "minor", 1.0),
    (re.compile(r"\b(no\s+blood|not\s+bleeding)\b", re.I),
     "bleeding", "none", 1.0),
]

# Scene risks. Hit → add to scene_risk list with weight (as a list-field
# booster handled separately below).
_SCENE_RULES: List[Tuple[re.Pattern, str, float]] = [
    (re.compile(r"\b(fire|flames?|burning)\b", re.I), "fire", 2.0),
    (re.compile(r"\bsmoke\b", re.I), "smoke", 1.5),
    (re.compile(r"\b(traffic|road|highway|cars?\s+coming)\b", re.I), "traffic", 1.5),
    (re.compile(r"\b(water|drowning|pool|river)\b", re.I), "water", 1.5),
    (re.compile(r"\b(electrical|live\s+wire|shocked)\b", re.I), "electrical", 1.5),
    (re.compile(r"\b(gun|knife|weapon)\b", re.I), "weapon", 2.0),
    (re.compile(r"\b(fell|fallen|dropped)\b", re.I), "fall", 1.0),
]


@dataclass
class Utterance:
    ts: float
    text: str


class SpeechContext:
    """Rolling transcript buffer with keyword-based fusion."""

    def __init__(self, window_seconds: float = 30.0):
        self.window_seconds = window_seconds
        self._utterances: List[Utterance] = []

    def add_transcript(self, text: str) -> None:
        text = (text or "").strip()
        if not text:
            return
        self._utterances.append(Utterance(ts=time.time(), text=text))
        self._prune()

    def recent_text(self) -> str:
        self._prune()
        return " ".join(u.text for u in self._utterances)

    def apply(self, apply_boost, apply_scene_boost) -> None:
        """Walk through rules and inject boosts into the smoother.

        `apply_boost(field, label, weight)` — for scalar fields.
        `apply_scene_boost(risk, weight)`  — for the scene_risk list field.
        """
        self._prune()
        text = " ".join(u.text for u in self._utterances)
        if not text:
            return

        for pat, field, label, w in _RULES:
            if pat.search(text):
                apply_boost(field, label, w)

        for pat, risk, w in _SCENE_RULES:
            if pat.search(text):
                apply_scene_boost(risk, w)

    def _prune(self) -> None:
        cutoff = time.time() - self.window_seconds
        self._utterances = [u for u in self._utterances if u.ts >= cutoff]
