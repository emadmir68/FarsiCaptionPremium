from __future__ import annotations

import collections
import contextlib
import wave
from dataclasses import dataclass

import webrtcvad


@dataclass(frozen=True)
class Frame:
    bytes: bytes
    timestamp: float
    duration: float


def _read_wave(path: str) -> tuple[bytes, int]:
    with contextlib.closing(wave.open(path, "rb")) as wf:
        if wf.getnchannels() != 1 or wf.getsampwidth() != 2 or wf.getframerate() not in (8000, 16000, 32000, 48000):
            raise ValueError("VAD requires mono 16-bit PCM WAV at 8/16/32/48 kHz")
        return wf.readframes(wf.getnframes()), wf.getframerate()


def _frames(audio: bytes, sample_rate: int, frame_ms: int = 30):
    n = int(sample_rate * (frame_ms / 1000.0) * 2)
    offset = 0
    timestamp = 0.0
    duration = (n / 2) / sample_rate
    while offset + n <= len(audio):
        yield Frame(audio[offset:offset + n], timestamp, duration)
        timestamp += duration
        offset += n


def speech_intervals(path: str, aggressiveness: int = 2, padding_ms: int = 240) -> list[tuple[float, float]]:
    audio, rate = _read_wave(path)
    vad = webrtcvad.Vad(aggressiveness)
    frames = list(_frames(audio, rate))
    if not frames:
        return []

    num_padding = max(1, padding_ms // 30)
    ring = collections.deque(maxlen=num_padding)
    triggered = False
    start = 0.0
    regions: list[tuple[float, float]] = []

    for frame in frames:
        voiced = vad.is_speech(frame.bytes, rate)
        if not triggered:
            ring.append((frame, voiced))
            voiced_count = sum(1 for _, v in ring if v)
            if voiced_count > 0.75 * ring.maxlen:
                triggered = True
                start = ring[0][0].timestamp
                ring.clear()
        else:
            ring.append((frame, voiced))
            unvoiced_count = sum(1 for _, v in ring if not v)
            if unvoiced_count > 0.85 * ring.maxlen:
                end = frame.timestamp + frame.duration
                if end - start >= 0.12:
                    regions.append((max(0.0, start), end))
                triggered = False
                ring.clear()

    if triggered:
        end = frames[-1].timestamp + frames[-1].duration
        regions.append((max(0.0, start), end))

    merged: list[tuple[float, float]] = []
    for a, b in regions:
        if merged and a - merged[-1][1] < 0.18:
            merged[-1] = (merged[-1][0], b)
        else:
            merged.append((a, b))
    return merged
