Persian-first offline ASR repair, version 3.3.1-ultra (43), arm64-v8a / Android 8+.

- Owned JNI bridge with pinned whisper.cpp v1.7.6; checks Turbo 128-mel / 4-decoder architecture, explicit Persian/Arabic language and beam search configuration.
- Silero VAD with original-timeline offsets for both segments and words; complete final PCM block retained. Small remains the announced fallback when RAM is insufficient.
- Remove speculative vocabulary corrections and cross-project learning. Persian normalization never runs on Arabic.
- Preserve genuine repetitions and silence gaps. One-time job + URI delivery prevents stale captions and polling from replacing manual edits.
- Keep applicationId, existing signing certificate, project edits and style settings. Bundle Vazirmatn for exported Persian captions.
- Version, tag, APK filename and metadata are checked together; existing release files are never overwritten.

Validation: native contract tests, 38 Kotlin regression tests, Android release build and upgrade-certificate check are required by CI. See docs/ASR-AUDIT.md for the limited real-audio experiment and reproducible scoring.

This is a prerelease: the user's failing colloquial video was not supplied. Physical Android performance, actual JNI inference on a phone, diverse music/noise, Arabic acoustic accuracy and rendered-video QA remain unverified. A successful build alone does not establish transcription quality. First use downloads the selected model and VAD; transcription then runs offline without a backend or paid API.
