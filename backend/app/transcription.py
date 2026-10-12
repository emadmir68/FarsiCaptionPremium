from __future__ import annotations

import os
from pathlib import Path

import httpx

from .captioning import Word


class TranscriptionError(RuntimeError):
    pass


async def transcribe_fa(audio_path: str) -> list[Word]:
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise TranscriptionError("OPENAI_API_KEY is not configured on the server")

    model = os.getenv("TRANSCRIPTION_MODEL", "gpt-4o-transcribe").strip() or "gpt-4o-transcribe"
    url = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/") + "/audio/transcriptions"

    data = [
        ("model", model),
        ("language", "fa"),
        ("response_format", "verbose_json"),
        ("timestamp_granularities[]", "word"),
        ("timestamp_granularities[]", "segment"),
        ("temperature", "0"),
    ]
    prompt = os.getenv(
        "TRANSCRIPTION_PROMPT",
        "فقط گفتار انسانی فارسی را دقیق و با نگارش طبیعی پیاده‌سازی کن. موسیقی، سکوت، صدای محیط، افکت و برچسب‌هایی مثل Music را ننویس. نام اشخاص، مکان‌ها و واژه‌های محاوره‌ای را تا حد ممکن حفظ کن.",
    ).strip()
    if prompt:
        data.append(("prompt", prompt))
    headers = {"Authorization": f"Bearer {api_key}"}

    async with httpx.AsyncClient(timeout=httpx.Timeout(600.0, connect=30.0)) as client:
        with open(audio_path, "rb") as f:
            files = {"file": (Path(audio_path).name, f, "audio/wav")}
            r = await client.post(url, headers=headers, data=data, files=files)

    if r.status_code >= 400:
        detail = r.text[:1200]
        raise TranscriptionError(f"Transcription API failed ({r.status_code}): {detail}")

    payload = r.json()
    raw_words = payload.get("words") or []
    words: list[Word] = []
    for item in raw_words:
        token = str(item.get("word", "")).strip()
        if not token:
            continue
        try:
            words.append(Word(token, float(item["start"]), float(item["end"])))
        except (KeyError, TypeError, ValueError):
            continue

    if words:
        return words

    for seg in payload.get("segments") or []:
        text = str(seg.get("text", "")).strip()
        tokens = text.split()
        if not tokens:
            continue
        try:
            start, end = float(seg["start"]), float(seg["end"])
        except (KeyError, TypeError, ValueError):
            continue
        span = max(0.2, end - start)
        step = span / len(tokens)
        for i, token in enumerate(tokens):
            words.append(Word(token, start + i * step, min(end, start + (i + 1) * step)))

    if not words:
        raise TranscriptionError("No Persian speech was detected")
    return words
