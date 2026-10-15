#!/usr/bin/env python3
"""Offline Persian ASR A/B gate. No app changes, uploads, or network calls.

Input CSV columns: wav,reference[,whisper]. Paths relative to CSV directory.
The optional whisper column contains the *existing app's* transcript.
Install locally: pip install sherpa-onnx soundfile numpy
Run: python scripts/benchmark_shenava.py --model-dir /path/to/shenava --manifest samples.csv --output results.json
"""
import argparse
import csv
import json
import re
import time
import unicodedata
from pathlib import Path


def normalize(text):
    text = unicodedata.normalize("NFC", text)
    text = text.translate(str.maketrans({"ي": "ی", "ى": "ی", "ك": "ک"}))
    text = re.sub(r"[\u200c\u200e\u200f\u202a-\u202e\u2066-\u2069]", " ", text)
    text = re.sub(r"[^\w\s]", " ", text, flags=re.UNICODE)
    return " ".join(text.split())


def edit_distance(a, b):
    previous = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        current = [i]
        for j, y in enumerate(b, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (x != y)))
        previous = current
    return previous[-1]


def scores(reference, hypothesis):
    ref = normalize(reference)
    hyp = normalize(hypothesis)
    words = ref.split()
    return {
        "wer": edit_distance(words, hyp.split()) / max(1, len(words)),
        "cer": edit_distance(list(ref.replace(" ", "")), list(hyp.replace(" ", ""))) / max(1, len(ref.replace(" ", ""))),
        "reference_words": len(words),
        "reference_chars": len(ref.replace(" ", "")),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--output", type=Path)\n    parser.add_argument("--self-test", action="store_true", help="Run metric/normalization regression tests and exit")
    parser.add_argument("--threads", type=int, default=2)
    args = parser.parse_args()
    model = args.model_dir
    for filename in ("model.onnx", "tokens.txt"):
        if not (model / filename).is_file():
            parser.error(f"Missing model file: {model / filename}")
    import numpy as np
    import sherpa_onnx
    import soundfile as sf
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(model / "model.onnx"), tokens=str(model / "tokens.txt"),
        num_threads=max(1, args.threads))
    with args.manifest.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows or any(not r.get("wav") or not r.get("reference") for r in rows):
        parser.error("Manifest requires nonempty wav and reference columns")
    results = []
    totals = {"shenava_word_errors": 0, "shenava_char_errors": 0,
              "whisper_word_errors": 0, "whisper_char_errors": 0,
              "reference_words": 0, "reference_chars": 0, "whisper_samples": 0}
    for row in rows:
        path = args.manifest.parent / row["wav"]
        audio, rate = sf.read(str(path), dtype="float32")
        if rate != 16000 or audio.ndim != 1:
            raise ValueError(f"{path}: expected 16 kHz mono PCM WAV; do not silently resample")
        started = time.monotonic()
        stream = recognizer.create_stream()
        stream.accept_waveform(rate, np.asarray(audio, dtype=np.float32))
        recognizer.decode_stream(stream)
        elapsed = time.monotonic() - started
        transcript = stream.result.text
        result = scores(row["reference"], transcript)
        word_errors = round(result["wer"] * result["reference_words"])
        char_errors = round(result["cer"] * result["reference_chars"])
        totals["shenava_word_errors"] += word_errors
        totals["shenava_char_errors"] += char_errors
        totals["reference_words"] += result["reference_words"]
        totals["reference_chars"] += result["reference_chars"]
        entry = {"wav": row["wav"], "shenava": transcript, "shenava_metrics": result,
                 "processing_seconds": elapsed, "audio_seconds": len(audio) / rate}
        if row.get("whisper", "").strip():
            other = scores(row["reference"], row["whisper"])
            entry["whisper_metrics"] = other
            totals["whisper_word_errors"] += round(other["wer"] * other["reference_words"])
            totals["whisper_char_errors"] += round(other["cer"] * other["reference_chars"])
            totals["whisper_samples"] += 1
        results.append(entry)
    summary = {"shenava_wer": totals["shenava_word_errors"] / max(1, totals["reference_words"]),
               "shenava_cer": totals["shenava_char_errors"] / max(1, totals["reference_chars"]),
               "samples": len(results), "whisper_samples": totals["whisper_samples"],
               "note": "Do not compare aggregate Whisper metrics unless every row includes Whisper output."}
    if totals["whisper_samples"] == len(results):
        summary["whisper_wer"] = totals["whisper_word_errors"] / max(1, totals["reference_words"])
        summary["whisper_cer"] = totals["whisper_char_errors"] / max(1, totals["reference_chars"])
    args.output.write_text(json.dumps({"summary": summary, "results": results},
                                      ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
