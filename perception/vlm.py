"""OpenAI vision client.

One function in, one Observation out. Everything async; the caller is
responsible for rate limiting (see pipeline.py).
"""

from __future__ import annotations

import base64
import json
import logging
import os
from typing import Optional

import httpx

from models import BODY_PARTS, SCENE_RISKS, Observation
from prompts import VISION_SYSTEM, user_turn

log = logging.getLogger("vlm")

OPENAI_URL = "https://api.openai.com/v1/chat/completions"


class VLMError(RuntimeError):
    pass


class VLMClient:
    def __init__(
        self,
        api_key: str,
        model: str = "gpt-4o-mini",
        timeout_s: float = 15.0,
    ):
        if not api_key:
            raise ValueError("OpenAI API key missing")
        self.api_key = api_key
        self.model = model
        self.timeout_s = timeout_s
        self._client = httpx.AsyncClient(timeout=timeout_s)

    async def close(self) -> None:
        await self._client.aclose()

    async def describe_frame(
        self, jpeg_bytes: bytes, frame_index: int
    ) -> Optional[Observation]:
        """Run one VLM call. Returns None on transport error so the caller
        can decide whether to retry or drop."""
        data_url = "data:image/jpeg;base64," + base64.b64encode(jpeg_bytes).decode()
        payload = {
            "model": self.model,
            "temperature": 0.0,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": VISION_SYSTEM},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": user_turn(frame_index)},
                        {
                            "type": "image_url",
                            "image_url": {"url": data_url, "detail": "low"},
                        },
                    ],
                },
            ],
        }
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        try:
            r = await self._client.post(OPENAI_URL, headers=headers, json=payload)
        except httpx.HTTPError as e:
            log.warning("VLM transport error: %s", e)
            return None

        if r.status_code != 200:
            log.warning("VLM http %s: %s", r.status_code, r.text[:200])
            return None

        try:
            raw = r.json()["choices"][0]["message"]["content"]
            parsed = json.loads(raw)
        except (KeyError, IndexError, ValueError) as e:
            log.warning("VLM response parse error: %s", e)
            return None

        return self._sanitize(parsed)

    @staticmethod
    def _sanitize(raw: dict) -> Observation:
        """Drop any out-of-vocabulary values the model hallucinated. Missing
        fields fall back to schema defaults."""

        def clean_list(value, vocab):
            if not isinstance(value, list):
                return []
            return [v for v in value if isinstance(v, str) and v in vocab]

        return Observation(
            bleeding=raw.get("bleeding", "unknown"),
            conscious=raw.get("conscious", "unknown"),
            breathing=raw.get("breathing", "unknown"),
            body_parts_visible=clean_list(raw.get("body_parts_visible"), BODY_PARTS),
            scene_risk=clean_list(raw.get("scene_risk"), SCENE_RISKS),
            person_visible=raw.get("person_visible", "no"),
            confidence=max(0.0, min(1.0, float(raw.get("confidence", 0.0)))),
            notes=str(raw.get("notes", ""))[:200],
        )


def from_env() -> VLMClient:
    """Construct from environment. Accepts EXPO_PUBLIC_* fallbacks so the
    existing project .env works without renaming."""
    api_key = (
        os.getenv("OPENAI_API_KEY")
        or os.getenv("EXPO_PUBLIC_OPENAI_API_KEY")
        or ""
    )
    model = (
        os.getenv("OPENAI_VISION_MODEL")
        or os.getenv("EXPO_PUBLIC_OPENAI_MODEL")
        or "gpt-4o-mini"
    )
    return VLMClient(api_key=api_key, model=model)
