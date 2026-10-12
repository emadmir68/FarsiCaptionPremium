from __future__ import annotations

from dataclasses import dataclass
import html
import re
from typing import Iterable


NON_SPEECH_PATTERNS = {
    "music", "[music]", "(music)", "موسیقی", "[موسیقی]", "آهنگ", "[آهنگ]",
    "applause", "[applause]", "تشویق", "[تشویق]", "silence", "[silence]",
}


@dataclass(frozen=True)
class Word:
    text: str
    start: float
    end: float


@dataclass(frozen=True)
class Caption:
    text: str
    start: float
    end: float
    words: tuple[Word, ...] = ()


def clean_word(text: str) -> str:
    text = (text or "").replace("\u200c", "‌").strip()
    text = re.sub(r"\s+", " ", text)
    if text.casefold() in NON_SPEECH_PATTERNS:
        return ""
    if re.fullmatch(r"[\[\(\{].{1,30}[\]\)\}]", text):
        return ""
    return text


def midpoint_in_speech(start: float, end: float, intervals: Iterable[tuple[float, float]], pad: float = 0.10) -> bool:
    mid = (start + end) / 2.0
    for a, b in intervals:
        if a - pad <= mid <= b + pad:
            return True
    return False


def filter_words(words: Iterable[Word], speech_intervals: list[tuple[float, float]]) -> list[Word]:
    out: list[Word] = []
    for w in words:
        t = clean_word(w.text)
        if not t:
            continue
        if speech_intervals and not midpoint_in_speech(w.start, w.end, speech_intervals):
            continue
        out.append(Word(t, max(0.0, float(w.start)), max(float(w.start) + 0.03, float(w.end))))
    return out


def group_words(
    words: list[Word],
    *,
    max_words: int = 5,
    max_chars: int = 34,
    max_duration: float = 2.8,
    gap_break: float = 0.48,
) -> list[Caption]:
    if not words:
        return []

    captions: list[Caption] = []
    buf: list[Word] = []

    def flush() -> None:
        nonlocal buf
        if not buf:
            return
        text = " ".join(w.text for w in buf).strip()
        if text:
            captions.append(Caption(text=text, start=buf[0].start, end=max(buf[-1].end, buf[0].start + 0.22), words=tuple(buf)))
        buf = []

    for w in words:
        if not buf:
            buf = [w]
            continue

        gap = w.start - buf[-1].end
        candidate_text = " ".join(x.text for x in [*buf, w])
        candidate_duration = w.end - buf[0].start

        must_break = (
            gap > gap_break
            or len(buf) >= max_words
            or len(candidate_text) > max_chars
            or candidate_duration > max_duration
        )
        if must_break:
            flush()
            buf = [w]
        else:
            buf.append(w)

    flush()

    adjusted: list[Caption] = []
    for i, c in enumerate(captions):
        end = c.end
        if i + 1 < len(captions):
            next_start = captions[i + 1].start
            end = min(end + 0.06, max(c.start + 0.18, next_start - 0.025))
        else:
            end += 0.06
        adjusted.append(Caption(c.text, c.start, end, c.words))
    return adjusted


def _srt_time(seconds: float) -> str:
    ms = max(0, round(seconds * 1000))
    h, rem = divmod(ms, 3_600_000)
    m, rem = divmod(rem, 60_000)
    s, ms = divmod(rem, 1000)
    return f"{h:02}:{m:02}:{s:02},{ms:03}"


def to_srt(captions: list[Caption]) -> str:
    blocks = []
    for i, c in enumerate(captions, 1):
        blocks.append(f"{i}\n{_srt_time(c.start)} --> {_srt_time(c.end)}\n{c.text}\n")
    return "\n".join(blocks)


def _ass_time(seconds: float) -> str:
    cs = max(0, round(seconds * 100))
    h, rem = divmod(cs, 360_000)
    m, rem = divmod(rem, 6_000)
    s, cs = divmod(rem, 100)
    return f"{h}:{m:02}:{s:02}.{cs:02}"


def _ass_escape_plain(text: str) -> str:
    text = html.unescape(text)
    text = text.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")
    return text.replace("\n", "\\N")


def _ass_escape(text: str) -> str:
    return "\u200f" + _ass_escape_plain(text)


def to_ass(
    captions: list[Caption],
    *,
    font_name: str = "Noto Sans Arabic",
    font_size: int = 52,
    margin_v: int = 90,
    progressive: bool = True,
    active_word_highlight: bool = False,
) -> str:
    header = f"""[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920
ScaledBorderAndShadow: yes
WrapStyle: 2

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Premium,{font_name},{font_size},&H00FFFFFF,&H0000D7FF,&H00111111,&H7F000000,-1,0,0,0,100,100,0,0,1,3.2,1.2,2,70,70,{margin_v},1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    events = []
    for c in captions:
        if progressive and c.words:
            for i, w in enumerate(c.words):
                start = max(c.start, w.start)
                end = c.end if i == len(c.words) - 1 else min(c.end, max(start + 0.05, c.words[i + 1].start - 0.012))
                previous = " ".join(x.text for x in c.words[:i]).strip()
                current = _ass_escape_plain(w.text)
                if active_word_highlight:
                    current = r"{\c&H00D7FF&}" + current + r"{\c&HFFFFFF&}"
                body = "\u200f" + (_ass_escape_plain(previous) + (" " if previous else "") + current).strip()
                events.append(
                    f"Dialogue: 0,{_ass_time(start)},{_ass_time(end)},Premium,,0,0,0,,{body}"
                )
        else:
            events.append(
                f"Dialogue: 0,{_ass_time(c.start)},{_ass_time(c.end)},Premium,,0,0,0,,{_ass_escape(c.text)}"
            )
    return header + "\n".join(events) + "\n"
