# Additional bundled dependencies
- whisper.cpp / ggml: MIT, pinned commit a8d002cfd879315632a579e73f0148d06959de36. See whisper-LICENSE. Model weights are downloaded separately from the model URLs in LocalCaptionEngine.kt.
- Vazirmatn Regular: SIL Open Font License 1.1, https://github.com/rastikerdar/vazirmatn . See ../android/app/src/main/assets/fonts/OFL.txt.
- Existing FFmpegKit dependency and its native codecs retain their upstream licensing requirements; this change does not add a cloud service or proprietary ASR SDK.
- Persian Turbo model: givia, [source and original training-data credits](https://huggingface.co/givia/whisper-large-v3-turbo-persian/blob/9626d38558f04c02f480b702c9db740eea567911/README.md), CC BY 4.0. Converted from supplied GGML FP16 to Q5_0 using the pinned whisper.cpp quantizer; no retraining. The complete model notice is bundled in the APK and provided with the separate release asset. Generic Whisper models remain available and Arabic does not select the Persian fine-tune.

# Development-only verification
- Paparazzi 1.3.5: Apache-2.0, https://github.com/cashapp/paparazzi . Used to render production Compose screens in CI; not an app runtime dependency.
- Google FLEURS Persian read-speech benchmark: Google FLEURS contributors, CC BY 4.0, https://huggingface.co/datasets/google/fleurs . Selected references/hashes and scores document evaluation; recordings are downloaded separately and are not bundled in the APK.
