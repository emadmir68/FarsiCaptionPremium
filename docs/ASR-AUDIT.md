# Persian ASR audit — 3.3.1-ultra

Base: b74d7d8976ce70aeaf7bf7de026e5a8a9d50e11f. applicationId remains com.farsicaption.premium. This audit inspected the repository/history, wrapper source and native decoder; it does not treat prior changes or a green build as evidence of ASR accuracy.

## Confirmed problems and changes

1. The previous Android wrapper exposed maxSegmentLength and printTimestamps in Kotlin without forwarding them to JNI, and did not expose the decoding/VAD controls required here. Its WAV-reading loop dropped a final partial block returned together with end-of-stream. Replace it with a small owned JNI bridge and PCM parser (4103-sample regression).
2. Pin whisper.cpp v1.7.6, commit a8d002cfd879315632a579e73f0148d06959de36 with archive SHA256. Verify the loaded model architecture: Turbo 128 mel bins / 4 decoder layers, Small 80 / 12. Use explicit fa/ar, beam 5, no carried text context, token timestamps, non-speech token suppression and decoder no-speech/log-probability thresholds. No unsupported promise that these choices are optimal for every speaker.
3. Audio preprocessing decodes to 16 kHz mono PCM16 without speculative denoising, compression or equalization. External Silero VAD selects speech ranges. Experiments exposed compressed token timestamps when using this whisper.cpp release's built-in VAD: add original sample offsets to BOTH word and segment timestamps instead. Bound speech windows to 28 seconds.
4. Prefix subtraction and adjacent text deduplication deleted real repeated speech. Only cumulative snapshots sharing the same start are replaced; preserve independently timed repeated utterances. Keep native segment intervals when word alignment is missing or invalid; do not fabricate evenly spaced word timestamps. Empty/silence output stays empty.
5. Remove dictionary guesses and automatically learned word replacement from the transcription/export path. Keep old preference data without applying it. Persian normalization handles NFC, yeh/kaf, explicit ZWNJ and punctuation, with a narrow morphological rule for unambiguous verb prefixes. Arabic bypasses Persian normalization. Spoken words such as «موسیقی» are not blacklisted; only explicit non-speech markers are stripped.
6. Polling previously replaced manually edited captions every 700 ms. Results are now accepted once for an exact job ID + URI. New media detaches the previous job and cancels its service; native inference is serialized and cancellable. Preserve project data when returning Home and provide resume. Save style preferences and word timings.
7. Export bundles Vazirmatn and passes its directory to libass; strings stay in logical RTL order. Visual export still requires phone/video QA.
8. Previous CI uploaded newly built bytes to v3.2.8-stable-alpha. CI now derives version/filename from built metadata, validates applicationId and versionCode, compares the APK signing certificate with the existing release, and publishes a new matching immutable version. Missing signing key fails the build instead of silently generating an incompatible key.

## Real-audio experiment

Two read-speech recordings were selected as the first two regular files in the Google FLEURS Persian development archive, before observing recognition results. CPU inference used the same C++ core as Android, Small and Turbo models, beam 5 and external VAD. This is a tiny diagnostic sample, not a colloquial benchmark or a comparison with the old APK.

| Recording | Duration | Reference words/chars | Small word/char errors | Turbo word/char errors | Vosk small-fa-0.42 word/char errors |
|---|---:|---:|---:|---:|---:|
| 10049006222377318397.wav | 9.54 s | 15 / 63 | 10 / 13 | 2 / 3 | 4 / 4 |
| 10097326041910827097.wav | 10.56 s | 16 / 57 | 10 / 12 | 3 / 6 | 2 / 2 |
| Aggregate | 20.10 s | 31 / 120 | WER 64.52%; CER 20.83% | WER 16.13%; CER 7.50% | WER 19.35%; CER 5.00% |

Normalization for scoring: NFC, ي/ى→ی, ك→ک, ZWNJ→space, strip Unicode punctuation and combining marks, collapse whitespace. WER operates on words; CER excludes spaces. No lexical corrections. See scripts/score_asr.py. Vosk has lower CER on these two clips; the sample cannot establish which engine is generally best. Keep Turbo primary as requested, with Small fallback. Do not replace the engine based on incomparable published benchmark numbers.

PCM16 16 kHz mono SHA256 (after ffmpeg conversion):
- 10049006222377318397.wav: 977ec84aad7ef26cc419d9a8eed07c69438f796ca695e9d4487460fd513d974f
- 10097326041910827097.wav: 99e50935c85b7598e2c477090f0576158cb2d61af0a1ed0d3980fb4b0762a2a4

Additional actual inference checks: three seconds of digital silence and three seconds of 440 Hz tone both returned zero segments. Repeating the first speech clip with three seconds of silence retained both utterances, with segment intervals 0.950–4.760, 4.760–7.560, 13.590–17.310, 17.310–19.990 seconds (22.08-second audio). All returned word intervals in the final four Small/Turbo sample runs fell within their parent segment. A tone is not a representative music test.

Local native contract and 38 Kotlin tests plus Android release build passed before automatic workspace pruning. The source was reconstructed from the session's recorded edits and checkpointed in GitHub; the final commit must pass CI again. Raw temporary inference outputs and the test-signed local APK were lost during pruning, so they are not offered as downloadable evidence. The published APK must come from CI with the existing signing certificate.

## Reproduction and remaining gates

Build the native probe with CMake 3.22+: `cmake -S android/app/src/main/cpp -B native-build -DCMAKE_BUILD_TYPE=Release`, `cmake --build native-build -j2`, `ctest --test-dir native-build --output-on-failure`. Run `native-build/asr_probe MODEL PCM16_WAV VAD_MODEL fa 128 4` for Turbo, or `fa 80 12` for Small. JSON is written to stdout; diagnostics go to stderr. Compare normalized predictions with official dev.tsv using the scoring script. CI runs native contract tests and production Kotlin pipeline regressions.

Not tested: the user's failing video (not attached); representative colloquial Persian/noise/music corpora; physical Android inference, thermal/RAM limits and latency; complete Android lifecycle/UI instrumentation; export visual/font/timing QA on device; Arabic acoustic quality. Android ABI support is arm64-v8a. The RAM heuristic requires at least 6 GB total, 3 GB available and no low-memory signal for Turbo; this cannot guarantee allocation success on every device. Turbo loading/inference failure attempts Small with a visible status; failure of both reports an error; users should not mistake a downloaded model or build success for quality validation.

## Primary sources and alternatives

- [Previous wrapper v1.0.0](https://github.com/ffmpegkit-maintained/whisper/tree/v1.0.0)
- [Pinned whisper.cpp](https://github.com/ggml-org/whisper.cpp/tree/a8d002cfd879315632a579e73f0148d06959de36)
- [OpenAI Turbo model card](https://huggingface.co/openai/whisper-large-v3-turbo): distilled large-v3 decoder, compatible architecture checked above.
- [GGML models](https://huggingface.co/ggerganov/whisper.cpp), [Silero GGML VAD](https://huggingface.co/ggml-org/whisper-vad). Downloads use exact sizes and SHA256 checks, atomic replacement and cancellation.
- [Google FLEURS](https://huggingface.co/datasets/google/fleurs), [Persian dev labels](https://huggingface.co/datasets/google/fleurs/resolve/main/data/fa_ir/dev.tsv), [Persian dev audio](https://huggingface.co/datasets/google/fleurs/resolve/main/data/fa_ir/audio/dev.tar.gz). Attribution: Google FLEURS contributors, CC BY 4.0. Audio is not bundled in the app.
- [Vosk official models](https://alphacephei.com/vosk/models): small-fa-0.42 is a much smaller offline alternative and was included in the two-clip experiment. Published benchmark scores are on different evaluation sets and do not prove superiority on this user's videos.
- [vhdm/whisper-large-fa-v1](https://huggingface.co/vhdm/whisper-large-fa-v1) reports its own clean-validation result and limitations on noise/accents. [sadrasa97 Persian Turbo](https://huggingface.co/sadrasa97/whisper-large-v3-turbo-fa) did not provide a comparable numeric evaluation in the reviewed card. Neither is substituted without representative audio, reproducible conversion and phone memory/latency validation.
