# Pinned whisper.cpp a8d002c: copy and score the actual SOT logits row.
# Without this correction no_speech reads zero/stale logits (often 1/n_vocab).
# See docs/ASR-RESEARCH-3.4.1.md; fail closed if upstream changes.
set(_whisper_cpp "${WHISPER_SOURCE_DIR}/src/whisper.cpp")
file(SHA256 "${_whisper_cpp}" _whisper_sha)
if(_whisper_sha STREQUAL "cf3e1e1d1bd1f91649c946d8f4b87b079fe6f0847cdfbffeaf3239f8a028fc1c")
    file(READ "${_whisper_cpp}" _whisper_source)
    string(REPLACE [=[                    whisper_compute_logprobs(state->logits, n_logits, logprobs);
                    whisper_compute_probs(state->logits, n_logits, logprobs, probs);]=] [=[                    const auto sot_begin = state->logits.begin() + sot_index*n_logits;
                    const std::vector<float> sot_logits(sot_begin, sot_begin + n_logits);
                    whisper_compute_logprobs(sot_logits, n_logits, logprobs);
                    whisper_compute_probs(sot_logits, n_logits, logprobs, probs);]=] _whisper_source "${_whisper_source}")
    string(REPLACE [=[                whisper_batch_prep_legacy(state->batch, prompt.data(), prompt.size(), 0, 0);
]=] [=[                whisper_batch_prep_legacy(state->batch, prompt.data(), prompt.size(), 0, 0);

                // FarsiCaption: no-speech belongs to the SOT row, not the last prompt token.
                const int sot_index = prompt.size() - prompt_init.size();
                WHISPER_ASSERT(prompt[sot_index] == whisper_token_sot(ctx));
                state->batch.logits[sot_index] = 1;
]=] _whisper_source "${_whisper_source}")
    file(WRITE "${_whisper_cpp}" "${_whisper_source}")
    file(SHA256 "${_whisper_cpp}" _whisper_sha)
endif()
if(NOT _whisper_sha STREQUAL "b36883ea3df6835a6d8b21bad1a97767624254d6303593f1e1cdbbe2a50e66bc")
    message(FATAL_ERROR "Unexpected whisper.cpp source; review the SOT/no-speech patch before building")
endif()
