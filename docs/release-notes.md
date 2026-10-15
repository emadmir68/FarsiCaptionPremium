Version 3.4.1-stability (46), arm64-v8a / Android 8+.

- Offline transcription now recovers from native Turbo Q5 decoding failures by retrying with Whisper Small rather than failing the entire video. Full Turbo still retries Q5 first and then Small if needed.
- Cancellation remains authoritative; it does not trigger a model fallback.
- Existing app ID, signing key, project data, settings, subtitle export and model caches are unchanged.
- No paid API, server or unverified Shenava model was added.
- This is a stability improvement for memory-constrained devices, not a claimed WER/CER accuracy gain.

The experimental Shenava accuracy comparison remains isolated in PR #2 and is not part of this release.

Version 3.4.0-studio (45), arm64-v8a / Android 8+.

- Refreshed Persian studio interface: ink / lilac / turquoise palette, consistent Vazirmatn type, compact video preview, clearer import and resume actions, and caption-by-caption editing without the old 60-caption limit.
- Independent landscape font preference. At 16:9 / 1080p, the new default is 81 px instead of the previous 43 px. Preview and export share the same scale; landscape size can be adjusted separately through 150. Portrait preferences remain intact.
- Offline recognition retains the settings of 3.3.2. A wider beam was tested on 20 labeled Persian recordings but rejected: held-out character error increased from 5.40% to 5.85%. The development-only improvement is not presented as an accuracy gain.
- Existing application ID, signing certificate, saved projects and model downloads are retained. No paid speech API or backend is used.

Validation: 65 JVM tests (including five actual Compose renders), native decoder contract, FFmpeg audio-timeline regression, signed Android build and matching upgrade certificate. Production ASS typography was visually inspected at normal and maximum landscape sizes.

Limitations: the speech benchmark is a small public read-speech sample with male-only metadata, not a measure of arbitrary colloquial or music-backed videos. Physical-device performance has not been tested. Full results: docs/ASR-BENCHMARK-3.4.0.md in the repository.
