"""FastAPI entry point.

Endpoints:
    POST /frame          multipart JPEG ingestion
    POST /audio          multipart audio-clip ingestion (wav/webm/mp3/ogg)
    POST /transcript     JSON {"text": "..."} for pre-transcribed speech
    GET  /latest         current smoothed PipelineOutput as JSON
    GET  /healthz        basic health probe
    WS   /observations   stream PipelineOutput JSON as they're produced

Run:  uvicorn app:app --host 0.0.0.0 --port 8090
"""

from __future__ import annotations

import asyncio
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional

from dotenv import load_dotenv
from fastapi import FastAPI, File, Form, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from pydantic import BaseModel

# Load keys from .env in this directory, falling back to the project root
# .env so the existing keys there are picked up without duplication.
load_dotenv(Path(__file__).parent / ".env")
load_dotenv(Path(__file__).parent.parent / ".env", override=False)

import stt as stt_module  # noqa: E402
import vlm as vlm_module  # noqa: E402
from pipeline import Pipeline  # noqa: E402

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s | %(message)s",
)
log = logging.getLogger("app")


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except ValueError:
        return default


def _float_env(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except ValueError:
        return default


@asynccontextmanager
async def lifespan(app: FastAPI):
    vlm = vlm_module.from_env()
    stt = stt_module.from_env()
    pipeline = Pipeline(
        vlm=vlm,
        stt=stt,
        buffer_size=_int_env("PERCEPTION_BUFFER_SIZE", 6),
        min_interval_ms=_int_env("PERCEPTION_MIN_INTERVAL_MS", 800),
        speech_window_s=_float_env("PERCEPTION_SPEECH_WINDOW_S", 30.0),
    )
    await pipeline.start()
    app.state.pipeline = pipeline
    log.info("perception service ready (model=%s)", vlm.model)
    try:
        yield
    finally:
        await pipeline.stop()


app = FastAPI(title="POV Perception Service", lifespan=lifespan)


def _pipeline(app: FastAPI) -> Pipeline:
    return app.state.pipeline  # type: ignore[no-any-return]


# --- REST ---

@app.get("/healthz")
async def healthz():
    return {"ok": True}


@app.post("/frame", status_code=202)
async def post_frame(file: UploadFile = File(...)):
    if file.content_type not in (None, "image/jpeg", "image/jpg", "application/octet-stream"):
        raise HTTPException(415, detail=f"Expected JPEG, got {file.content_type}")
    body = await file.read()
    if not body:
        raise HTTPException(400, detail="Empty frame")
    await _pipeline(app).submit_frame(body)
    return {"queued": True, "bytes": len(body)}


@app.post("/audio", status_code=202)
async def post_audio(file: UploadFile = File(...)):
    body = await file.read()
    if not body:
        raise HTTPException(400, detail="Empty audio")
    text = await _pipeline(app).submit_audio(
        body, content_type=file.content_type or "audio/wav"
    )
    return {"transcript": text}


class TranscriptIn(BaseModel):
    text: str


@app.post("/transcript", status_code=202)
async def post_transcript(body: TranscriptIn):
    _pipeline(app).submit_transcript(body.text)
    return {"accepted": True}


@app.get("/latest")
async def get_latest():
    latest = _pipeline(app).latest()
    if latest is None:
        return {"observations": None}
    return latest.model_dump()


# --- WebSocket fan-out ---

@app.websocket("/observations")
async def ws_observations(ws: WebSocket):
    await ws.accept()
    pipeline = _pipeline(app)
    try:
        async for item in pipeline.subscribe():
            await ws.send_json(item.model_dump())
    except WebSocketDisconnect:
        return
    except Exception as e:
        log.warning("ws observations ended: %s", e)
