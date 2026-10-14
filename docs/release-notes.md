Version 3.4.0-studio (45), arm64-v8a / Android 8+.

- A refreshed Persian studio interface: ink / lilac / turquoise palette, consistent Vazirmatn type, compact video preview, clearer import and resume actions, and caption-by-caption editing without the old 60-caption limit.
- Independent landscape font preference. 16:9 captions use a larger shared preview/export scale; the new landscape default is 81 px at 1080p instead of the previous 43 px. Portrait preferences remain intact. Landscape size can be adjusted separately through 150.
- Quality remains offline Turbo / Turbo Q5. Recognition settings are evaluated against a reproducible Persian reference corpus; no unproven decoder change is promoted just because the build passes.
- Existing application ID, signing certificate, saved projects and model downloads are retained.

Validation details and ASR benchmark results will be recorded before release. Physical-device performance and accuracy on arbitrary colloquial videos cannot be inferred from desktop tests.
