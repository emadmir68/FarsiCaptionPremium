from __future__ import annotations

import os
import shutil
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask

from .captioning import filter_words, group_words, to_ass, to_srt
from .media import MediaError, burn_ass, duration_seconds, extract_audio_wav
from .transcription import TranscriptionError, transcribe_fa
from .vad import speech_intervals

app = FastAPI(title="Farsi Caption Premium API", version="1.0.0")


def _check_token(token: str | None) -> None:
    expected = os.getenv("APP_TOKEN", "").strip()
    if expected and token != expected:
        raise HTTPException(status_code=401, detail="Invalid app token")


@app.get("/health")
def health():
    return {"ok": True, "engine": os.getenv("TRANSCRIPTION_MODEL", "gpt-4o-transcribe"), "language": "fa"}


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
    srt = str(Path(job_dir) / "captions.srt")
    output = str(Path(job_dir) / "captioned.mp4")

    try:
        with open(source, "wb") as out:
            while True:
                chunk = await video.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)

        if os.path.getsize(source) < 1024:
            raise HTTPException(status_code=400, detail="Video file is empty")

        duration = duration_seconds(source)
        max_seconds = float(os.getenv("MAX_VIDEO_SECONDS", "600"))
        if duration > max_seconds:
            raise HTTPException(status_code=413, detail=f"Video is too long. Current limit: {int(max_seconds)} seconds")

        extract_audio_wav(source, wav)
        regions = speech_intervals(wav, aggressiveness=int(os.getenv("VAD_AGGRESSIVENESS", "2")))
        words = await transcribe_fa(wav)
        words = filter_words(words, regions)
        captions = group_words(words, max_words=max_words)

        if not captions:
            raise HTTPException(status_code=422, detail="No usable Persian speech was detected after speech filtering")

        Path(ass).write_text(
            to_ass(
                captions,
                font_size=font_size,
                progressive=progressive,
                active_word_highlight=highlight_active_word,
            ),
            encoding="utf-8",
        )
        Path(srt).write_text(to_srt(captions), encoding="utf-8")
        burn_ass(source, ass, output)

        headers = {
            "X-Caption-Count": str(len(captions)),
            "X-Speech-Region-Count": str(len(regions)),
            "X-Engine": os.getenv("TRANSCRIPTION_MODEL", "gpt-4o-transcribe"),
        }
        return FileResponse(
            output,
            media_type="video/mp4",
            filename="farsi-caption-premium.mp4",
            headers=headers,
            background=BackgroundTask(shutil.rmtree, job_dir, ignore_errors=True),
        )
    except HTTPException:
        shutil.rmtree(job_dir, ignore_errors=True)
        raise
    except (MediaError, TranscriptionError, ValueError) as exc:
        shutil.rmtree(job_dir, ignore_errors=True)
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    except Exception as exc:
        shutil.rmtree(job_dir, ignore_errors=True)
        raise HTTPException(status_code=500, detail=f"Unexpected processing error: {exc}") from exc
