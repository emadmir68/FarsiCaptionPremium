from __future__ import annotations

import difflib
import os
import re
from pathlib import Path

import httpx

from .captioning import Word


class TranscriptionError(RuntimeError):
    pass


def _normalize_token(value: str) -> str:
    value = value.strip().replace("ي", "ی").replace("ك", "ک")
    value = re.sub(r"[\u064B-\u065F\u0670]", "", value)
    value = re.sub(r"[^\w\u0600-\u06FF]+", "", value, flags=re.UNICODE)
    return value.casefold()


def _spread(tokens: list[str], start: float, end: float) -> list[Word]:
    tokens = [t.strip() for t in tokens if t.strip()]
    if not tokens:
        return []
    span = max(0.08 * len(tokens), end - start)
    step = span / len(tokens)
    out: list[Word] = []
    for i, token in enumerate(tokens):
        a = start + i * step
        b = start + (i + 1) * step
        out.append(Word(token, a, max(a + 0.04, b)))
    return out


def _align_accurate_text(timed: list[Word], accurate_text: str) -> list[Word]:
    accurate = [x for x in accurate_text.split() if x.strip()]
    if not timed or not accurate:
        return timed

    left = [_normalize_token(x.text) for x in timed]
    right = [_normalize_token(x) for x in accurate]
    matcher = difflib.SequenceMatcher(a=left, b=right, autojunk=False)

    if matcher.ratio() < 0.45:
        return timed

    out: list[Word] = []
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            for offset, token in enumerate(accurate[j1:j2]):
                source = timed[i1 + offset]
                out.append(Word(token, source.start, source.end))
        elif tag == "replace":
            if i1 < i2 and j1 < j2:
                out.extend(_spread(accurate[j1:j2], timed[i1].start, timed[i2 - 1].end))
        elif tag == "delete":
            continue
        elif tag == "insert":
            if j1 >= j2:
                continue
            prev_end = out[-1].end if out else (timed[i1 - 1].end if i1 > 0 else 0.0)
            next_start = timed[i1].start if i1 < len(timed) else prev_end + 0.12 * (j2 - j1)
            if next_start <= prev_end:
                next_start = prev_end + 0.08 * (j2 - j1)
            out.extend(_spread(accurate[j1:j2], prev_end, next_start))

    out.sort(key=lambda w: (w.start, w.end))
    return out or timed


async def _post_transcription(
    client: httpx.AsyncClient,
    *,
    api_key: str,
    audio_path: str,
    model: str,
    response_format: str,
    word_timestamps: bool = False,
) -> dict:
    url = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/") + "/audio/transcriptions"
    headers = {"Authorization": f"Bearer {api_key}"}
    prompt = os.getenv(
        "TRANSCRIPTION_PROMPT",
        "گفتار فارسی را دقیق، روان و با نگارش طبیعی پیاده‌سازی کن. واژه‌های محاوره‌ای، نام اشخاص و مکان‌ها را حفظ کن. موسیقی، سکوت، صدای محیط و افکت را متن نکن.",
    ).strip()

    data: list[tuple[str, str]] = [
        ("model", model),
        ("language", "fa"),
        ("response_format", response_format),
        ("temperature", "0"),
    ]
    if prompt:
        data.append(("prompt", prompt))
    if model == "gpt-transcribe":
        data.append(("chunking_strategy", "auto"))
    if word_timestamps:
        data.extend([
            ("timestamp_granularities[]", "word"),
            ("timestamp_granularities[]", "segment"),
        ])

    with open(audio_path, "rb") as f:
        files = {"file": (Path(audio_path).name, f, "audio/wav")}
        r = await client.post(url, headers=headers, data=data, files=files)

    if r.status_code >= 400:
        raise TranscriptionError(f"{model} failed ({r.status_code}): {r.text[:1000]}")
    return r.json()


async def transcribe_fa(audio_path: str) -> list[Word]:
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise TranscriptionError("OPENAI_API_KEY is not configured on the server")

    quality_model = os.getenv("QUALITY_TRANSCRIPTION_MODEL", "gpt-transcribe").strip() or "gpt-transcribe"
    timing_model = os.getenv("TIMING_TRANSCRIPTION_MODEL", "whisper-1").strip() or "whisper-1"

    async with httpx.AsyncClient(timeout=httpx.Timeout(600.0, connect=30.0)) as client:
        quality_payload = await _post_transcription(
            client,
            api_key=api_key,
            audio_path=audio_path,
            model=quality_model,
            response_format="json",
        )
        timing_payload = await _post_transcription(
            client,
            api_key=api_key,
            audio_path=audio_path,
            model=timing_model,
            response_format="verbose_json",
            word_timestamps=True,
        )

    raw_words = timing_payload.get("words") or []
    timed: list[Word] = []
    for item in raw_words:
        token = str(item.get("word", "")).strip()
        if not token:
            continue
        try:
            timed.append(Word(token, float(item["start"]), float(item["end"])))
        except (KeyError, TypeError, ValueError):
            continue

    if not timed:
        raise TranscriptionError("Timing model returned no word timestamps")

    accurate_text = str(quality_payload.get("text", "")).strip()
    if not accurate_text:
        return timed
    return _align_accurate_text(timed, accurate_text)
