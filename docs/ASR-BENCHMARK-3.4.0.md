# Persian reference benchmark — 3.4.0-studio

This is a small, reproducible diagnostic benchmark, not a claim of general Persian accuracy or parity with a commercial caption app. It does not replace testing the user's colloquial video.

## Data and selection

Source: [Google FLEURS](https://huggingface.co/datasets/google/fleurs), Persian `fa_ir`, revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`. Attribution: Google FLEURS contributors. License: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). These are read-speech recordings. The Persian dev/test metadata in this revision lists male recordings only; this sample does not establish performance across genders, accents, music or spontaneous conversation.

`scripts/prepare_fa_benchmark.py` selects the first ten distinct transcript IDs encountered in each dev/test archive, before viewing recognition results. Development transcript IDs are excluded from the test set. Official `dev.tsv` and `test.tsv` labels are the references; model-generated text is never treated as ground truth. The 10 test clips are held out from decoder selection. The original recordings are converted with ffmpeg to PCM16 mono 16 kHz, without denoising or lexical correction. Audio is not bundled with the app.

## Compared configuration

- Same native core as Android, pinned whisper.cpp `a8d002cfd879315632a579e73f0148d06959de36`.
- Turbo Q5 SHA256 `394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2`.
- Silero VAD SHA256 `29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf`.
- Baseline: production beam size 5. Candidate: beam size 8 only. Language, temperature fallback, audio, VAD and other settings are identical.
- Candidate overrides are compiled only into a separate host benchmark build. Android ignores `CAPTION_BENCHMARK_BEAM_SIZE` and retains the production setting unless an independently reviewed source change is made.
- Selection rule: candidate must reduce dev WER without increasing dev CER or empty transcriptions. Only then compare both on test; reject if test WER/CER or empty transcriptions worsen. A tie retains production. No tuning on test results.

## Metrics

WER is total word edits divided by total reference words, computed independently per clip and then aggregated. CER similarly excludes spaces. Normalization uses NFC, Arabic/Persian yeh and kaf equivalence, ZWNJ as a separator, removal of combining marks/punctuation, and whitespace collapse. No dictionary substitutions. Timing is host CPU wall time and is not an Android speed claim; concurrent benchmark runs share the CPU.

## Reproduction

```sh
python3 scripts/prepare_fa_benchmark.py /tmp/farsi-benchmark
cmake -S android/app/src/main/cpp -B native-build -DCMAKE_BUILD_TYPE=Release
cmake --build native-build --parallel 4
native-build/asr_batch MODEL VAD /tmp/farsi-benchmark/dev.tsv /tmp/farsi-benchmark/beam5-dev 128 4
# Use a separate build directory for the experimental host-only setting:
cmake -S android/app/src/main/cpp -B beam8-build -DCMAKE_BUILD_TYPE=Release -DCAPTION_BENCHMARK_BEAM_SIZE=8
cmake --build beam8-build --target asr_batch --parallel 4
beam8-build/asr_batch MODEL VAD /tmp/farsi-benchmark/dev.tsv /tmp/farsi-benchmark/beam8-dev 128 4
python3 scripts/report_fa_benchmark.py /tmp/farsi-benchmark/manifest.json dev /tmp/farsi-benchmark/beam5-dev
python3 scripts/report_fa_benchmark.py /tmp/farsi-benchmark/manifest.json dev /tmp/farsi-benchmark/beam8-dev
```

Results and decision are recorded below after the completed runs.
