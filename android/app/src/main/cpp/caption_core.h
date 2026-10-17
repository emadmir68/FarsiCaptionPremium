#pragma once
#include "whisper.h"
#include <atomic>
#include <string>
#include <utility>
#include <vector>
namespace caption {
struct Engine { whisper_context * ctx; std::atomic<bool> cancelled{false}; };
std::vector<float> read_pcm16_wav(const std::string & path);
whisper_full_params parameters(const char * language, int threads, const char * vad_path, bool fast_decode = false);
Engine * load(const std::string & path, int mels, int decoder_layers);
std::string transcribe(Engine & engine, const std::string & path, const std::string & vad, const std::string & language, int threads, bool fast_decode = false);
std::string escape(const std::string & value);
bool has_confident_speech(const float *probabilities, int count, int first_sample, int last_sample);
std::vector<std::pair<int,int>> coalesce_speech_windows(const std::vector<std::pair<int,int>> & windows, int max_gap_samples, int max_window_samples);
void release(Engine * engine);
}
