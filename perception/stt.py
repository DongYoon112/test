"""ElevenLabs speech-to-text client.

Accepts a short audio chunk (wav/webm/mp3/ogg) and returns transcribed text.
If the audio contains no speech, returns an empty string.
"""

from __future__ import annotations

import logging
import os
from typing import Optional

import httpx

log = logging.getLogger("stt")

ELEVENLABS_URL = "https://api.elevenlabs.io/v1/speech-to-text"


class STTClient:
    def __init__(
        self,
        api_key: str,
        model_id: str = "scribe_v1",
        timeout_s: float = 15.0,
    ):
        if not api_key:
            raise ValueError("ElevenLabs API key missing")
        self.api_key = api_key
        self.model_id = model_id
        self.timeout_s = timeout_s
        self._client = httpx.AsyncClient(timeout=timeout_s)

    async def close(self) -> None:
        await self._client.aclose()

    async def transcribe(
        self, audio_bytes: bytes, content_type: str = "audio/wav"
    ) -> Optional[str]:
        """Run one STT call. Returns transcript text or None on error."""
        if not audio_bytes:
            return ""

        files = {
            "file": ("clip", audio_bytes, content_type),
        }
        data = {"model_id": self.model_id}
        headers = {"xi-api-key": self.api_key}

        try:
            r = await self._client.post(
                ELEVENLABS_URL, headers=headers, files=files, data=data
            )
        except httpx.HTTPError as e:
            log.warning("STT transport error: %s", e)
            return None

        if r.status_code != 200:
            log.warning("STT http %s: %s", r.status_code, r.text[:200])
            return None

        try:
            body = r.json()
        except ValueError:
            log.warning("STT non-JSON response")
            return None

        return (body.get("text") or "").strip()


def from_env() -> STTClient:
    api_key = (
        os.getenv("ELEVENLABS_API_KEY")
        or os.getenv("EXPO_PUBLIC_ELEVENLABS_API_KEY")
        or ""
    )
    model_id = (
        os.getenv("ELEVENLABS_STT_MODEL_ID")
        or os.getenv("EXPO_PUBLIC_ELEVENLABS_STT_MODEL_ID")
        or "scribe_v1"
    )
    return STTClient(api_key=api_key, model_id=model_id)
