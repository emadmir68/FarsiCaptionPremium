from __future__ import annotations

import json
import subprocess


class MediaError(RuntimeError):
    pass


def _run(args: list[str]) -> None:
    p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if p.returncode != 0:
        raise MediaError(p.stderr[-3000:])


def duration_seconds(path: str) -> float:
    p = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", path],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if p.returncode != 0:
        raise MediaError(p.stderr[-1200:])
    try:
        return float(json.loads(p.stdout)["format"]["duration"])
    except Exception as exc:
        raise MediaError("Could not read video duration") from exc


def extract_audio_wav(video_path: str, wav_path: str) -> None:
    _run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", video_path,
        "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
        wav_path,
    ])


def burn_ass(video_path: str, ass_path: str, output_path: str) -> None:
    vf = f"ass={ass_path}:fontsdir=/usr/share/fonts/truetype/noto"
    _run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", video_path,
        "-vf", vf,
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
        "-c:a", "aac", "-b:a", "192k",
        "-movflags", "+faststart",
        output_path,
    ])
