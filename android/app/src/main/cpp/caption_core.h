#pragma once
#include "whisper.h"
#include <atomic>
#include <string>
#include <vector>
namespace caption {
struct Engine { whisper_context * ctx; std::atomic<bool> cancelled{false}; };
std::vector<float> read_pcm16_wav(const std::string & path);
whisper_full_params parameters(const char * language, int threads, const char * vad_path);
Engine * load(const std::string & path, int mels, int decoder_layers);
std::string transcribe(Engine & engine, const std::string & path, const std::string & vad, const std::string & language, int threads);
std::string escape(const std::string & value);
bool has_confident_speech(const float *probabilities, int count, int first_sample, int last_sample);
void release(Engine * engine);
}
