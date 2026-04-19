"""Temporal state tracker.

Replaces the Phase 1 smoothing buffer. Maintains per-field state
(value, confidence, stability, trend, duration, source attribution)
over a time-queryable buffer of recent Observations and speech boosts.

Design principles:
  * cautious — confidence builds with evidence, not with a single frame
  * vision-first — a confident visual signal cannot be flipped by speech
  * time-aware — trend and stability are computed over the window
  * honest — "unknown" is preserved; we never invent values
"""

from __future__ import annotations

import time
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import Deque, Dict, List, Optional

from models import (
    BODY_PARTS,
    FieldState,
    Observation,
    SCENE_RISKS,
    SmoothedState,
)

# ---- severity ordering ------------------------------------------------------
# Used for (a) tiebreaking "vote" decisions toward the more clinically
# significant label, and (b) detecting increasing/decreasing trends.
#
# Convention: severity is "worse is larger". "unknown" sits at 1 — a
# worse-than-best-known, better-than-worst-known default — so that moving
# from "unknown" to "no (unconscious)" still reads as increasing severity.

_SEVERITY: Dict[str, Dict[str, int]] = {
    "bleeding": {"none": 0, "unknown": 1, "minor": 1, "heavy": 2},
    "breathing": {"normal": 0, "unknown": 1, "abnormal": 1, "none": 2},
    "conscious": {"yes": 0, "unknown": 1, "no": 2},
    "person_visible": {"no": 0, "yes": 1},
}

# Speech cannot flip a visual winner with at least this much visual weight
# (and where the visual winner is not "unknown"). Tuning knob.
_STRONG_VISUAL_WEIGHT = 1.5


# ---- internal state ---------------------------------------------------------

@dataclass
class _Entry:
    ts: float
    obs: Observation


@dataclass
class _History:
    """Tracks when the current value was first entered, for duration_s."""
    value: str = ""
    entered_at: float = 0.0


# ---- tracker ----------------------------------------------------------------

class StateTracker:
    def __init__(
        self,
        capacity: int = 10,
        recency_half_life_s: float = 6.0,
        stable_run_frames: int = 3,
        maturity_full_at: int = 4,
    ):
        self._buf: Deque[_Entry] = deque(maxlen=capacity)
        self.recency_half_life_s = recency_half_life_s
        self.stable_run_frames = stable_run_frames
        self.maturity_full_at = maturity_full_at

        self._speech_boosts: Dict[str, Dict[str, float]] = defaultdict(dict)
        self._scene_boosts: Dict[str, float] = defaultdict(float)
        self._history: Dict[str, _History] = {}

    # ---- ingestion ----

    def add_frame(self, obs: Observation) -> None:
        self._buf.append(_Entry(ts=time.time(), obs=obs))

    def apply_speech_boost(self, field_name: str, label: str, weight: float) -> None:
        self._speech_boosts[field_name][label] = (
            self._speech_boosts[field_name].get(label, 0.0) + weight
        )

    def apply_scene_boost(self, risk: str, weight: float) -> None:
        self._scene_boosts[risk] = self._scene_boosts.get(risk, 0.0) + weight

    # ---- queries ----

    def size(self) -> int:
        return len(self._buf)

    def query_last_n(self, n: int) -> List[Observation]:
        if n <= 0:
            return []
        return [e.obs for e in list(self._buf)[-n:]]

    def query_last_seconds(self, seconds: float) -> List[Observation]:
        cutoff = time.time() - seconds
        return [e.obs for e in self._buf if e.ts >= cutoff]

    # ---- snapshot ----

    def snapshot(self) -> SmoothedState:
        """Compute per-field states; clear ephemeral speech boosts."""
        now = time.time()

        if not self._buf:
            default_unknown = FieldState(value="unknown")
            default_no = FieldState(value="no")
            result = SmoothedState(
                bleeding=default_unknown.model_copy(),
                breathing=default_unknown.model_copy(),
                conscious=default_unknown.model_copy(),
                person_visible=default_no.model_copy(),
                body_parts_visible=[],
                scene_risk=[],
                confidence=0.0,
            )
            self._speech_boosts.clear()
            self._scene_boosts.clear()
            return result

        fields = {
            f: self._field_state(f, now)
            for f in ("bleeding", "breathing", "conscious", "person_visible")
        }

        body_parts = self._list_field("body_parts_visible", now, boosts=None)
        scene_risk = self._list_field("scene_risk", now, boosts=self._scene_boosts)
        if "none" in scene_risk and len(scene_risk) > 1:
            scene_risk = [r for r in scene_risk if r != "none"]

        core = ("bleeding", "breathing", "conscious")
        global_conf = sum(fields[f].confidence for f in core) / len(core)

        # Clear one-shot boosts so they don't carry into the next snapshot.
        self._speech_boosts.clear()
        self._scene_boosts.clear()

        return SmoothedState(
            bleeding=fields["bleeding"],
            breathing=fields["breathing"],
            conscious=fields["conscious"],
            person_visible=fields["person_visible"],
            body_parts_visible=body_parts,
            scene_risk=scene_risk,
            confidence=global_conf,
        )

    # ---- per-field computation ----

    def _field_state(self, field_name: str, now: float) -> FieldState:
        # Visual scores: recency × per-frame confidence, summed per label.
        visual_scores: Dict[str, float] = defaultdict(float)
        confidences: List[float] = []
        for e in self._buf:
            label = getattr(e.obs, field_name)
            w = self._recency(e.ts, now) * max(0.05, e.obs.confidence)
            visual_scores[label] += w
            confidences.append(e.obs.confidence)

        speech_scores: Dict[str, float] = dict(self._speech_boosts.get(field_name, {}))

        visual_winner = _argmax_with_tiebreak(visual_scores, field_name)
        speech_winner = (
            _argmax_with_tiebreak(speech_scores, field_name) if speech_scores else None
        )

        combined: Dict[str, float] = defaultdict(float)
        for k, v in visual_scores.items():
            combined[k] += v
        for k, v in speech_scores.items():
            combined[k] += v
        combined_winner = _argmax_with_tiebreak(combined, field_name)

        # Speech-override guard: strong, non-"unknown" visual resists speech.
        if (
            speech_winner is not None
            and combined_winner != visual_winner
            and visual_winner != "unknown"
            and visual_scores.get(visual_winner, 0.0) >= _STRONG_VISUAL_WEIGHT
        ):
            winner = visual_winner
        else:
            winner = combined_winner

        # Source attribution on the winning label.
        v_share = visual_scores.get(winner, 0.0)
        s_share = speech_scores.get(winner, 0.0)
        total = v_share + s_share
        if total <= 1e-9:
            source = "vision"
            visual_support = 0.0
        else:
            vf = v_share / total
            visual_support = vf
            if vf >= 0.7:
                source = "vision"
            elif vf <= 0.3:
                source = "speech"
            else:
                source = "fused"

        # Confidence — cautious, builds with evidence.
        total_visual = sum(visual_scores.values()) or 1.0
        agreement = visual_scores.get(winner, 0.0) / total_visual

        vals = [getattr(e.obs, field_name) for e in self._buf]
        volatility = _volatility(vals)

        avg_frame_conf = sum(confidences) / len(confidences) if confidences else 0.0
        maturity = min(1.0, len(self._buf) / max(1.0, float(self.maturity_full_at)))

        confidence = agreement * avg_frame_conf * maturity * (1.0 - 0.4 * volatility)
        if s_share > 0 and speech_winner == winner:
            confidence = min(1.0, confidence + 0.1)
        confidence = max(0.0, min(0.95, confidence))

        # Stability: last K frames all equal AND low volatility.
        last_k = vals[-self.stable_run_frames:]
        stable = (len(set(last_k)) <= 1) and (volatility <= 0.2)
        stability = "stable" if stable else "fluctuating"

        # Trend: severity direction over the window.
        trend = _trend(field_name, vals)

        # Duration: continuous run of the winning value.
        duration_s = self._update_history(field_name, winner, now)

        return FieldState(
            value=winner,
            confidence=confidence,
            stability=stability,
            trend=trend,
            duration_s=duration_s,
            source=source,
            visual_support=visual_support,
        )

    def _list_field(
        self,
        field_name: str,
        now: float,
        boosts: Optional[Dict[str, float]],
    ) -> List[str]:
        vocab = BODY_PARTS if field_name == "body_parts_visible" else SCENE_RISKS
        scores: Dict[str, float] = defaultdict(float)
        for e in self._buf:
            w = self._recency(e.ts, now) * max(0.05, e.obs.confidence)
            for v in getattr(e.obs, field_name):
                if v in vocab:
                    scores[v] += w
        if boosts:
            for k, extra in boosts.items():
                if k in vocab:
                    scores[k] += extra
        threshold = 0.15
        return sorted(k for k, v in scores.items() if v >= threshold)

    # ---- helpers ----

    def _recency(self, ts: float, now: float) -> float:
        age = max(0.0, now - ts)
        return 0.5 ** (age / self.recency_half_life_s)

    def _update_history(self, field_name: str, new_value: str, now: float) -> float:
        hist = self._history.get(field_name)
        if hist is None or hist.value != new_value:
            # Walk back to see if this value was continuously held — this
            # recovers duration when snapshot() is called for the first time
            # on a buffer that already has a stable tail.
            entered = now
            for e in reversed(self._buf):
                if getattr(e.obs, field_name) == new_value:
                    entered = e.ts
                else:
                    break
            hist = _History(value=new_value, entered_at=entered)
            self._history[field_name] = hist
        return now - hist.entered_at


# ---- pure helpers -----------------------------------------------------------

def _argmax_with_tiebreak(scores: Dict[str, float], field_name: str) -> str:
    if not scores:
        return "unknown"
    sev = _SEVERITY.get(field_name, {})
    # Break ties toward the more severe label so we prefer to over-report.
    return max(scores.items(), key=lambda kv: (kv[1], sev.get(kv[0], 0)))[0]


def _volatility(values: List[str]) -> float:
    if len(values) < 2:
        return 0.0
    flips = sum(1 for i in range(1, len(values)) if values[i] != values[i - 1])
    return flips / (len(values) - 1)


def _trend(field_name: str, values: List[str]) -> str:
    sev_map = _SEVERITY.get(field_name)
    if not sev_map or len(values) < 3:
        return "unknown"
    nums = [sev_map.get(v) for v in values]
    nums = [n for n in nums if n is not None]
    if len(nums) < 3:
        return "unknown"
    half = len(nums) // 2
    first = sum(nums[:half]) / half
    second = sum(nums[half:]) / (len(nums) - half)
    diff = second - first
    if diff >= 0.5:
        return "increasing"
    if diff <= -0.5:
        return "decreasing"
    return "stable"
