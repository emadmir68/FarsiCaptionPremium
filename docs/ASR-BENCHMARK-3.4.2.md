# Persian ASR 3.4.2: measured improvement and limits

Release: 3.4.2-persian (47). Select **فارسی تخصصی** for the new offline model.
The saved model preference remains intact. The generic quality mode still uses
full Turbo or stock Turbo Q5 according to memory. Arabic never selects the
Persian fine-tune. Decoding failures retain the 3.4.1 recovery to Q5/Small.

## Results on actual recordings

All values below are pooled word/character edit counts, not averages of
per-clip percentages. Both engines use the same audio, references, beam 5,
explicit Persian, no previous-text conditioning and original sample offsets.
The native engine validates the actual 128-mel / 4-decoder-layer architecture.

| Fixed evaluation set | Stock Turbo Q5 WER | Persian Turbo Q5 WER | Stock CER | Persian CER |
| --- | ---: | ---: | ---: | ---: |
| FLEURS: 20 fresh read-speech recordings, 572 words | 20.63% (118 errors) | 9.27% (53 errors) | 5.48% | 3.23% |
| PSRB: 18 informal recordings, 396 words | 47.47% (188 errors) | 36.36% (144 errors) | 18.59% | 12.64% |

There were zero empty hypotheses and zero out-of-bounds segments in both sets.
The fixed development comparison also evaluated joined VAD windows and a
separate vhdm Persian model. Givia won that predeclared comparison; the joined
VAD experiment was not combined into production. The new mode uses the pinned
Givia checkpoint, quantized reproducibly to Q5_0. Its model asset is 574,041,195
bytes (about 548 MiB), SHA256:
`d832fe5cd81c756bc1962765cff4a33cacde00a7701809837875245870b6d5a8`.

The initial candidate failed instrumental-music control. We corrected an
upstream bug that read stale/uncomputed logits for no-speech probability, but
that alone did not fix the fine-tune's music hallucinations. A separate Persian
VAD check now requires a 256 ms confident speech seed while retaining the
original boundaries and padding. The same 38 recordings were rerun for both
models after these changes: aggregate WER, CER, empty outputs and valid bounds
were unchanged. These reruns are regression evidence, not another unseen test.

Both final engines produced zero text on the fixed digital-silence, pure-tone
and 20-second instrumental-music controls. Both occurrences of real repeated
speech separated by 3 seconds of silence survived; errors remained 4/30 words
for stock and 2/30 for the Persian model. No word-dictionary substitutions were
introduced. Persian orthographic normalization and Arabic isolation remain.

## Verification and reproducibility

- [Controlled model comparison](https://github.com/emadmir68/FarsiCaptionPremium/actions/runs/38606792364): all four development arms and the selected held-out pair completed.
- [Independent informal evaluation](https://github.com/emadmir68/FarsiCaptionPremium/actions/runs/38607589009): both models completed; the original failed music result is retained.
- [Native correction regression](https://github.com/emadmir68/FarsiCaptionPremium/actions/runs/38694071591): all 14 deterministic execution shards completed.
- [Android verification](https://github.com/emadmir68/FarsiCaptionPremium/actions/runs/38694071555): 72 JVM tests, five actual Compose renders, native contract tests, FFmpeg timeline regression, signed APK and upgrade-certificate checks passed.
- [Machine-readable results](benchmarks/fa-3.4.2.json): original and corrected reports, manifests, hashes, model provenance and control results.
- [Protocol and root-cause investigation](ASR-RESEARCH-3.4.1.md): selection rules, source review and native patch rationale.

PSRB's research-use recordings, references and recognition text are not
redistributed. Public FLEURS references retain their Google FLEURS CC-BY-4.0
attribution. The user's private video was checked locally (five nonempty,
bounded segments); no video, audio or transcript was uploaded to GitHub.

## Practical limits

These are small descriptive evaluations, not a claim of universal accuracy or
zero errors. The model author used FLEURS-dev for checkpoint selection; training
set overlap is not completely documented. PSRB provides an additional informal
speech check, but 18 clips do not represent every accent, noise level or video.

The private clip has no human-verified reference, so its WER is unknown and its
recognition problem is not claimed solved. Valid timestamp bounds do not prove
word-level alignment accuracy. Physical Android speed, RAM pressure, thermal
behavior and visual playback have not been tested on a device. Host timings
are recorded but are not Android performance claims. Very weak or brief speech
can still be missed by VAD; instrumental music beyond the fixed control can
still cause errors. The comparison is against stock Q5, not full-precision Turbo.
