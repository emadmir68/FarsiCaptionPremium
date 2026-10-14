# Persian ASR follow-up experiment

Base production version: 3.4.0-studio, commit 30f345783422fbf636df8423dffb384e10393641. Research only until the acceptance gates below are met. No API, backend, dictionary substitution or automatic text rewriting is introduced.

## Protocol fixed before fresh-test decoding

Development: the same 10 public FLEURS Persian dev recordings used in 3.4.0, with the official references. Baseline is rerun using a fresh native context for every recording, matching separate Android jobs and eliminating inherited sampling RNG state. This makes resuming incomplete runs consistent.

Candidates, each compared independently with production:

1. Keep the exact Turbo Q5 model and beam 5. Join adjacent external-VAD ranges only if the original gap is at most 1.0 s and the resulting window is at most 28.0 s. Preserve the original samples, including the gap; never concatenate speech or compress time. No language prompt/context is carried across windows. Experimental change is host-only and Persian-only.
2. Keep baseline VAD/decoder settings and substitute vhdm/whisper-large-fa-v1, revision 95485391406953c01ffc06724af5a08cb517a325 (MIT). Convert from its safetensors using the pinned upstream whisper.cpp conversion mapping and quantize with that upstream revision to Q5_0. This is a separate candidate, not a combination with candidate 1. Model-card scores are not used as our measurements.
3. Added before any fresh-test decoding: givia/whisper-large-v3-turbo-persian, revision 9626d38558f04c02f480b702c9db740eea567911 (CC-BY-4.0), supplied GGML FP16 quantized to Q5_0 using the same pinned engine. Baseline VAD/decoder settings are retained. This author's training includes conversational speech; its own scores are not our evidence. Its checkpoint selection used FLEURS-dev, so development performance is not an independent validation of this candidate.

Selection: a candidate must strictly improve dev WER, not increase dev CER or empty outputs, and have valid segment bounds. Compare the best qualifying candidate (lowest dev WER, then CER) against baseline on 20 fresh FLEURS test recordings. These are the first 20 distinct transcript IDs in the archive after excluding every ID in the old 20-clip manifest. Do not tune on the fresh test. Reject if aggregate test WER, CER or empty outputs increase. Also check silence, repeated speech separated by silence, and the supplied private video. No WER is assigned to that video without a verified transcript.

The new test references are fixed before recognition. Data provenance and attribution remain Google FLEURS, fa_ir, revision 70bb2e84b976b7e960aa89f1c648e09c59f894dd, CC-BY-4.0. All clips are read speech; this cannot establish general colloquial/noisy-video accuracy or physical Android speed. Fine-tune training-data overlap is not fully documented by its author, so an improvement on FLEURS alone would require additional caution before selecting it as a default.

## Primary-source review

- [whisper.cpp source](https://github.com/ggml-org/whisper.cpp/tree/a8d002cfd879315632a579e73f0148d06959de36): existing model, sample offsets and C++ behavior are inspected directly.
- [vhdm Persian Turbo](https://huggingface.co/vhdm/whisper-large-fa-v1): compatible 128-mel / 4-decoder-layer architecture and standard Whisper token IDs; author's evaluation uses its own curated clean-speech dataset.
- [BuzzASR Persian](https://huggingface.co/BuzzASR/persian): promising published Persian results, but its replaced tokenizer uses vocab size 53474 and decoder-start ID 51866, unlike the current native bridge's standard Whisper special-token convention. It is not a drop-in model replacement. No Android result is claimed.
- [Qwen3-ASR 0.6B](https://huggingface.co/Qwen/Qwen3-ASR-0.6B): Persian is supported, but its published FLEURS additional-language result aggregates ten languages, and its separate forced aligner does not list Persian. Current Whisper JNI cannot execute this architecture.
- [sherpa-onnx Omnilingual CTC](https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/models.html): another offline runtime route with a 300M int8 model. No same-corpus superiority is assumed from architecture or model size.

## Audio inspection

The supplied video is stereo, 48 kHz AAC. Decoded channel correlation is about 0.82 and the mono downmix is about -24.1 dBFS RMS, with no clipped samples in the decoded inspection. Phase cancellation is not the observed cause in this clip. Existing timestamp-preserving 16 kHz extraction is retained. No speculative equalization or noise removal is applied.

Results will be appended after the controlled runs.

## Durable execution

Incomplete local experiments were lost after the workspace was restored to an earlier snapshot; they are not reported as completed results. The controlled evaluation now runs in GitHub Actions. All four development arms must finish before the fixed selection rule runs. Only baseline and the selected candidate see the fresh test. Raw public hypotheses, reference manifests, model hashes, timings and logs are retained as Actions artifacts. No user audio or private transcript is uploaded. Android production behavior remains unchanged until the gates pass. Synthetic non-speech and repeat controls plus an independent conversational evaluation remain required before a fine-tune becomes the default.

Independent informal-speech protocol, fixed before its decoding: PartAI/PSRB revision 626745e790667d6cbf70bdb10a260d3af90ed2d2; take the first two numerical audio IDs per each of the nine source categories, restricted to informal speech, 3–30 seconds, with no unintelligible-span marker. This yields 18 clips across animation, audiobook, debate, documentary, film, lecture, news, podcast and talk show. Preserve accents/noise/genders as provided. The pinned CSV has misplaced text/duration/speaker-count headers, explicitly validated by the script. PSRB is research/education-only; no recordings or reference/hypothesis text are redistributed, only IDs and metrics. Require aggregate WER/CER and empty-output counts not to increase against the unchanged baseline. These small per-category groups are descriptive, not a statistical guarantee.

Controls: 3 seconds of digital silence, 3 seconds of 440 Hz tone, the first 20 seconds of instrumental Vibe Ace (Kevin MacLeod, CC-BY-3.0), and the first FLEURS dev recording repeated with a 3-second silent gap. Require no speech text on the non-speech controls, both real occurrences preserved, and bounded segment times. Timing bounds are not a measurement of human word-alignment accuracy. The private user clip is checked locally, never uploaded to Actions or GitHub.
