"""Pydantic schemas for observations and pipeline output."""

from __future__ import annotations

from typing import List, Literal
from pydantic import BaseModel, Field, ConfigDict

Bleeding = Literal["none", "minor", "heavy", "unknown"]
Conscious = Literal["yes", "no", "unknown"]
Breathing = Literal["normal", "abnormal", "none", "unknown"]
PersonVisible = Literal["yes", "no"]

BODY_PARTS = {
    "face", "head", "neck", "chest", "abdomen",
    "arm", "hand", "leg", "foot", "back",
}
SCENE_RISKS = {
    "fire", "smoke", "traffic", "water", "electrical",
    "crowd", "weapon", "fall", "cold", "heat", "none",
}


class Observation(BaseModel):
    """Single-frame observation from the VLM, or a smoothed roll-up."""
    model_config = ConfigDict(extra="ignore")

    bleeding: Bleeding = "unknown"
    conscious: Conscious = "unknown"
    breathing: Breathing = "unknown"
    body_parts_visible: List[str] = Field(default_factory=list)
    scene_risk: List[str] = Field(default_factory=list)
    person_visible: PersonVisible = "no"
    confidence: float = 0.0
    notes: str = ""


Stability = Literal["stable", "fluctuating"]
Trend = Literal["increasing", "decreasing", "stable", "unknown"]
Source = Literal["vision", "speech", "fused"]


class FieldState(BaseModel):
    """Per-field state tracked over time.

    `duration_s` = seconds the current value has been continuously held.
    `visual_support` in [0,1] = fraction of the winning weight that came
        from vision rather than speech.
    """
    model_config = ConfigDict(extra="ignore")

    value: str
    confidence: float = 0.0
    stability: Stability = "fluctuating"
    trend: Trend = "unknown"
    duration_s: float = 0.0
    source: Source = "vision"
    visual_support: float = 0.0


class SmoothedState(BaseModel):
    """Rolled-up per-field states + list fields."""
    model_config = ConfigDict(extra="ignore")

    bleeding: FieldState
    breathing: FieldState
    conscious: FieldState
    person_visible: FieldState
    body_parts_visible: List[str] = Field(default_factory=list)
    scene_risk: List[str] = Field(default_factory=list)
    confidence: float = 0.0


class PipelineOutput(BaseModel):
    """What we emit downstream after state tracking + speech fusion."""
    timestamp: float
    states: SmoothedState
    summary: str
    frames_in_buffer: int
    recent_speech: str = ""
