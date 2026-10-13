Version 3.3.2-ultra (44), arm64-v8a / Android 8+. Follow-up to a user-supplied source clip and rendered result.

- Quality mode never automatically falls back to Small. It uses full Turbo with enough memory, otherwise verified Turbo Q5 (~548 MB download). Small is available only through the explicit lightweight choice.
- Preserve the model name in the completed job status. A downloaded model or "ready" label is not an accuracy guarantee.
- Preserve delayed audio and timestamp gaps during extraction. Real ffmpeg regression fixtures cover zero offset, delayed audio and an internal gap.
- Use the video aspect ratio in the preview, the bundled Persian font, and the same relative font size / bottom margin in preview and export. Extend the font control through 110 and provide a readable-size shortcut without erasing saved preferences.
- Balance aligned caption groups instead of splitting six words into five plus one, preserve pauses, and enable line wrapping in export.
- applicationId, update-signing certificate, project data and previous releases remain unchanged.

Important result: recognition errors were reproduced on the supplied real clip. Turbo, Turbo Q5 and the full Large-v3 comparison did NOT establish accurate recognition of this sample. An experimental Persian fine-tune omitted most speech and was rejected. This release fixes demonstrated selection, timing and presentation defects; it is NOT a claim that the sample's ASR is solved. No dictionary guesses or replacement transcript is injected.

CI gates: native contract, 50 Kotlin unit/regression tests, real ffmpeg timeline tests, release APK build, application/version checks and signing-certificate match. Physical Android inference/thermal performance and end-to-end device lifecycle remain untested. User media and recognition text are not published in the repository.
