from __future__ import annotations

import json
import os
import shutil
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from starlette.background import BackgroundTask

from .captioning import Caption, Word, filter_words, group_words, to_ass, to_srt
from .media import MediaError, burn_ass, duration_seconds, extract_audio_wav
from .transcription import TranscriptionError, transcribe_fa
from .vad import speech_intervals

app = FastAPI(title="Farsi Caption Premium API", version="2.0.0")


def _check_token(token: str | None) -> None:
    expected = os.getenv("APP_TOKEN", "").strip()
    if expected and token != expected:
        raise HTTPException(status_code=401, detail="Invalid app token")


def _cleanup(path: str) -> None:
    shutil.rmtree(path, ignore_errors=True)


async def _prepare_words(source: str, wav: str) -> tuple[list[Word], list[tuple[float, float]]]:
    extract_audio_wav(source, wav)
    regions = speech_intervals(wav, aggressiveness=int(os.getenv("VAD_AGGRESSIVENESS", "2")))
    words = await transcribe_fa(wav)
    words = filter_words(words, regions)
    return words, regions


def _caption_to_dict(c: Caption) -> dict:
    return {
        "text": c.text,
        "start": round(c.start, 3),
        "end": round(c.end, 3),
        "words": [
            {"text": w.text, "start": round(w.start, 3), "end": round(w.end, 3)}
            for w in c.words
        ],
    }


def _rebuild_words(text: str, start: float, end: float) -> tuple[Word, ...]:
    tokens = [x for x in text.split() if x.strip()]
    if not tokens:
        return ()
    span = max(0.12, end - start)
    step = span / len(tokens)
    return tuple(
        Word(token, start + i * step, max(start + i * step + 0.04, start + (i + 1) * step))
        for i, token in enumerate(tokens)
    )


def _captions_from_json(raw: str) -> list[Caption]:
    payload = json.loads(raw)
    if not isinstance(payload, list):
        raise ValueError("captions_json must be a list")
    out: list[Caption] = []
    for item in payload:
        text = str(item.get("text", "")).strip()
        start = float(item.get("start", 0))
        end = float(item.get("end", start + 0.2))
        if not text or end <= start:
            continue
        raw_words = item.get("words") or []
        words: list[Word] = []
        for w in raw_words:
            try:
                words.append(Word(str(w["text"]).strip(), float(w["start"]), float(w["end"])))
            except Exception:
                pass
        joined = " ".join(w.text for w in words).strip()
        if not words or joined != text:
            words = list(_rebuild_words(text, start, end))
        out.append(Caption(text, start, end, tuple(words)))
    return out


@app.get("/health")
def health():
    return {
        "ok": True,
        "version": "2.0.0",
        "language": "fa",
        "quality_engine": os.getenv("QUALITY_TRANSCRIPTION_MODEL", "gpt-transcribe"),
        "timing_engine": os.getenv("TIMING_TRANSCRIPTION_MODEL", "whisper-1"),
    }


@app.post("/v2/transcribe")
async def transcribe_video(
    video: UploadFile = File(...),
    max_words: int = Form(5),
    x_app_token: str | None = Header(default=None),
):
    _check_token(x_app_token)
    max_words = max(2, min(max_words, 8))
    job_dir = tempfile.mkdtemp(prefix="fa_transcribe_")
    source = str(Path(job_dir) / "source.mp4")
    wav = str(Path(job_dir) / "speech.wav")
    try:
        with open(source, "wb") as out:
            while True:
                chunk = await video.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)

        duration = duration_seconds(source)
        max_seconds = float(os.getenv("MAX_VIDEO_SECONDS", "600"))
        if duration > max_seconds:
            raise HTTPException(status_code=413, detail=f"Video is too long. Current limit: {int(max_seconds)} seconds")

        words, regions = await _prepare_words(source, wav)
        captions = group_words(words, max_words=max_words)
        if not captions:
            raise HTTPException(status_code=422, detail="No usable Persian speech was detected")

        return JSONResponse({
            "language": "fa",
            "duration": round(duration, 3),
            "full_text": " ".join(c.text for c in captions),
            "speech_regions": [{"start": round(a, 3), "end": round(b, 3)} for a, b in regions],
            "captions": [_caption_to_dict(c) for c in captions],
        })
    finally:
        _cleanup(job_dir)


@app.post("/v2/export")
async def export_video(
    video: UploadFile = File(...),
    captions_json: str = Form(...),
    preset: str = Form("luxury"),
    font_size: int = Form(54),
    margin_v: int = Form(110),
    progressive: bool = Form(True),
    x_app_token: str | None = Header(default=None),
):
    _check_token(x_app_token)
    job_dir = tempfile.mkdtemp(prefix="fa_export_")
    source = str(Path(job_dir) / "source.mp4")
    ass = str(Path(job_dir) / "captions.ass")
    output = str(Path(job_dir) / "captioned.mp4")
    try:
        with open(source, "wb") as out:
            while True:
                chunk = await video.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)

        captions = _captions_from_json(captions_json)
        if not captions:
            raise HTTPException(status_code=422, detail="No captions to export")

        Path(ass).write_text(
            to_ass(
                captions,
                font_size=max(34, min(font_size, 82)),
                margin_v=max(30, min(margin_v, 420)),
                progressive=progressive,
                active_word_highlight=False,
                preset=preset,
            ),
            encoding="utf-8",
        )
        burn_ass(source, ass, output)
        return FileResponse(
            output,
            media_type="video/mp4",
            filename="farsi-caption-premium.mp4",
            background=BackgroundTask(_cleanup, job_dir),
        )
    except HTTPException:
        _cleanup(job_dir)
        raise
    except Exception as exc:
        _cleanup(job_dir)
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/v1/caption")
async def caption_video(
    video: UploadFile = File(...),
    max_words: int = Form(5),
    font_size: int = Form(52),
    progressive: bool = Form(True),
    highlight_active_word: bool = Form(False),
    x_app_token: str | None = Header(default=None),
):
    _check_token(x_app_token)
    max_words = max(2, min(max_words, 8))
    font_size = max(34, min(font_size, 76))

    job_dir = tempfile.mkdtemp(prefix="fa_caption_")
    source = str(Path(job_dir) / "source.mp4")
    wav = str(Path(job_dir) / "speech.wav")
    ass = str(Path(job_dir) / "captions.ass")
    output = str(Path(job_dir) / "captioned.mp4")

    try:
        with open(source, "wb") as out:
            while True:
                chunk = await video.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)

        words, _ = await _prepare_words(source, wav)
        captions = group_words(words, max_words=max_words)
        if not captions:
            raise HTTPException(status_code=422, detail="No usable Persian speech was detected")

        Path(ass).write_text(
            to_ass(
                captions,
                font_size=font_size,
                progressive=progressive,
                active_word_highlight=highlight_active_word,
                preset="luxury",
            ),
            encoding="utf-8",
        )
        burn_ass(source, ass, output)
        return FileResponse(
            output,
            media_type="video/mp4",
            filename="farsi-caption-premium.mp4",
            background=BackgroundTask(_cleanup, job_dir),
        )
    except HTTPException:
        _cleanup(job_dir)
        raise
    except (MediaError, TranscriptionError, ValueError) as exc:
        _cleanup(job_dir)
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    except Exception as exc:
        _cleanup(job_dir)
        raise HTTPException(status_code=500, detail=f"Unexpected processing error: {exc}") from exc
