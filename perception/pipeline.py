"""Pipeline orchestrator.

Owns the smoothing buffer, speech context, and the single in-flight VLM
worker. Ingestion endpoints call `submit_frame` / `submit_transcript`;
consumers subscribe via `subscribe()` for a stream of smoothed outputs.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from typing import AsyncIterator, Optional

from fusion import SpeechContext
from models import PipelineOutput, SmoothedState
from state import StateTracker
from stt import STTClient
from vlm import VLMClient

log = logging.getLogger("pipeline")


@dataclass
class FrameJob:
    jpeg: bytes
    received_at: float


class Pipeline:
    def __init__(
        self,
        vlm: VLMClient,
        stt: STTClient,
        buffer_size: int = 6,
        min_interval_ms: int = 800,
        speech_window_s: float = 30.0,
    ):
        self.vlm = vlm
        self.stt = stt
        self.min_interval_s = min_interval_ms / 1000.0

        self.tracker = StateTracker(capacity=buffer_size)
        self.speech = SpeechContext(window_seconds=speech_window_s)

        # Slot for the next frame to process. Dropping-latest semantics:
        # only the most recent frame is kept if we fall behind.
        self._pending: Optional[FrameJob] = None
        self._pending_lock = asyncio.Lock()
        self._wake = asyncio.Event()

        # Fan-out of PipelineOutput to any subscribers.
        self._subscribers: list[asyncio.Queue[PipelineOutput]] = []
        self._latest: Optional[PipelineOutput] = None

        self._worker_task: Optional[asyncio.Task] = None
        self._frame_counter = 0
        self._last_vlm_call_at: float = 0.0

    # ---------- lifecycle ----------

    async def start(self) -> None:
        if self._worker_task is None:
            self._worker_task = asyncio.create_task(self._vlm_loop())

    async def stop(self) -> None:
        if self._worker_task:
            self._worker_task.cancel()
            try:
                await self._worker_task
            except asyncio.CancelledError:
                pass
        await self.vlm.close()
        await self.stt.close()

    # ---------- ingestion ----------

    async def submit_frame(self, jpeg: bytes) -> None:
        async with self._pending_lock:
            # Drop any older queued frame — always process the newest.
            self._pending = FrameJob(jpeg=jpeg, received_at=time.time())
        self._wake.set()

    async def submit_audio(self, blob: bytes, content_type: str) -> str:
        """Transcribe audio and push the text into the speech context.
        Returns the transcript (may be empty)."""
        text = await self.stt.transcribe(blob, content_type=content_type)
        if text:
            self.speech.add_transcript(text)
        return text or ""

    def submit_transcript(self, text: str) -> None:
        """Fast path for when the client runs STT locally and sends text."""
        self.speech.add_transcript(text)

    # ---------- output ----------

    def latest(self) -> Optional[PipelineOutput]:
        return self._latest

    async def subscribe(self) -> AsyncIterator[PipelineOutput]:
        q: asyncio.Queue[PipelineOutput] = asyncio.Queue(maxsize=16)
        self._subscribers.append(q)
        try:
            # Prime the subscriber with the most recent value if any.
            if self._latest is not None:
                await q.put(self._latest)
            while True:
                item = await q.get()
                yield item
        finally:
            if q in self._subscribers:
                self._subscribers.remove(q)

    # ---------- worker ----------

    async def _vlm_loop(self) -> None:
        """Single consumer: pops the latest pending frame, runs VLM, pushes
        into buffer, emits smoothed output. One request in flight at a
        time — naturally rate-limits GPT-4o costs."""
        while True:
            await self._wake.wait()
            self._wake.clear()

            # Rate limit by wall clock — even if frames flood in, don't
            # issue VLM calls more often than min_interval_s.
            since = time.time() - self._last_vlm_call_at
            if since < self.min_interval_s:
                await asyncio.sleep(self.min_interval_s - since)

            async with self._pending_lock:
                job = self._pending
                self._pending = None

            if job is None:
                continue

            self._last_vlm_call_at = time.time()
            self._frame_counter += 1
            try:
                obs = await self.vlm.describe_frame(job.jpeg, self._frame_counter)
            except Exception as e:  # safety net; VLMClient already catches transport errors
                log.exception("VLM call raised: %s", e)
                obs = None

            if obs is None:
                # Bad frame, bad response, or API timeout. Don't pollute the
                # buffer with a fabricated observation; just skip.
                continue

            # Apply speech-derived boosts before the next snapshot(), so the
            # tracker considers them for both voting and source attribution.
            self.speech.apply(
                apply_boost=self.tracker.apply_speech_boost,
                apply_scene_boost=self.tracker.apply_scene_boost,
            )
            self.tracker.add_frame(obs)
            state = self.tracker.snapshot()
            output = PipelineOutput(
                timestamp=time.time(),
                states=state,
                summary=_summarize(state),
                frames_in_buffer=self.tracker.size(),
                recent_speech=self.speech.recent_text(),
            )
            self._latest = output
            await self._fanout(output)

    async def _fanout(self, output: PipelineOutput) -> None:
        dead = []
        for q in self._subscribers:
            try:
                q.put_nowait(output)
            except asyncio.QueueFull:
                dead.append(q)
        for q in dead:
            # Slow subscribers get dropped rather than backpressuring the pipeline.
            if q in self._subscribers:
                self._subscribers.remove(q)


def _summarize(state: SmoothedState) -> str:
    """Human-readable summary built from the tracked states.

    Includes trend wording when a field is increasing/decreasing and flags
    speech-only signals so downstream consumers know not to treat them as
    confirmed visual findings.
    """
    if state.person_visible.value == "no":
        return "No person in frame."

    parts: list[str] = []

    b = state.bleeding
    if b.value not in ("unknown", "none"):
        phrase = f"{b.value} bleeding"
        if b.trend == "increasing":
            phrase += ", appears to be increasing"
        elif b.trend == "decreasing":
            phrase += ", appears to be improving"
        parts.append(phrase)

    c = state.conscious
    if c.value == "no":
        phrase = "appears unconscious"
        if c.trend == "increasing":
            phrase = "consciousness deteriorating"
        parts.append(phrase)
    elif c.value == "yes":
        parts.append("conscious")

    br = state.breathing
    if br.value not in ("unknown", "normal"):
        phrase = {"none": "possibly not breathing", "abnormal": "breathing abnormal"}[br.value]
        if br.source == "speech":
            phrase += " (reported by voice; not visually confirmed)"
        elif br.source == "fused":
            phrase += " (voice + visual)"
        parts.append(phrase)

    if state.scene_risk:
        parts.append("scene risk: " + ", ".join(state.scene_risk))

    if not parts:
        return "Person visible; no acute signs detected."
    return "Person visible; " + "; ".join(parts) + "."
