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


def group_words(words: list[Word], *, max_words: int = 5, max_chars: int = 36, max_duration: float = 2.8, gap_break: float = 0.48) -> list[Caption]:
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
        if gap > gap_break or len(buf) >= max_words or len(candidate_text) > max_chars or candidate_duration > max_duration:
            flush()
            buf = [w]
        else:
            buf.append(w)
    flush()
    return captions


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


def _style_for(preset: str) -> tuple[str, str, str, int, float, float]:
    p = (preset or "luxury").lower()
    if p == "bold":
        return "&H00FFFFFF", "&H0000D7FF", "&H00101010", 1, 4.4, 1.4
    if p == "neon":
        return "&H00F7FFF8", "&H0000FF9D", "&H00160B23", 1, 4.0, 2.0
    if p == "glass":
        return "&H00FFFFFF", "&H00E8D6B0", "&H66000000", 3, 1.6, 0.0
    if p == "minimal":
        return "&H00FFFFFF", "&H00FFFFFF", "&H00111111", 1, 2.8, 0.6
    return "&H00FFF8E8", "&H006BCBFF", "&H00100D08", 1, 3.8, 1.2


def to_ass(
    captions: list[Caption],
    *,
    font_name: str = "Noto Sans Arabic",
    font_size: int = 52,
    margin_v: int = 110,
    progressive: bool = True,
    active_word_highlight: bool = False,
    preset: str = "luxury",
) -> str:
    primary, secondary, back, border_style, outline, shadow = _style_for(preset)
    header = f"""[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920
ScaledBorderAndShadow: yes
WrapStyle: 2

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Premium,{font_name},{font_size},{primary},{secondary},&H00111111,{back},-1,0,0,0,100,100,0,0,{border_style},{outline},{shadow},2,70,70,{margin_v},1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    events: list[str] = []
    for c in captions:
        if progressive and c.words:
            for i, w in enumerate(c.words):
                start = max(c.start, w.start)
                end = c.end if i == len(c.words) - 1 else min(c.end, max(start + 0.05, c.words[i + 1].start - 0.012))
                previous = " ".join(x.text for x in c.words[:i]).strip()
                current = _ass_escape_plain(w.text)
                body = "\u200f" + (_ass_escape_plain(previous) + (" " if previous else "") + current).strip()
                events.append(f"Dialogue: 0,{_ass_time(start)},{_ass_time(end)},Premium,,0,0,0,,{body}")
        else:
            body = "\u200f" + _ass_escape_plain(c.text)
            events.append(f"Dialogue: 0,{_ass_time(c.start)},{_ass_time(c.end)},Premium,,0,0,0,,{body}")
    return header + "\n".join(events) + "\n"
