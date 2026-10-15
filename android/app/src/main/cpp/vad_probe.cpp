#include "caption_core.h"
#include <iostream>
// Host diagnostic only: inspect frame probabilities without loading an ASR model.
int main(int argc, char **argv) {
    if (argc != 3) return 2;
    try {
        auto audio = caption::read_pcm16_wav(argv[2]);
        auto params = whisper_vad_default_context_params();
        params.n_threads = 2; params.use_gpu = false;
        auto *ctx = whisper_vad_init_from_file_with_params(argv[1], params);
        if (!ctx) return 1;
        if (!whisper_vad_detect_speech(ctx, audio.data(), audio.size())) {
            whisper_vad_free(ctx); return 1;
        }
        std::cout << "frame,probability\n";
        auto *probs = whisper_vad_probs(ctx);
        for (int i = 0; i < whisper_vad_n_probs(ctx); ++i) std::cout << i << ',' << probs[i] << '\n';
        whisper_vad_free(ctx);
        return 0;
    } catch (const std::exception &e) { std::cerr << e.what() << '\n'; return 1; }
}
