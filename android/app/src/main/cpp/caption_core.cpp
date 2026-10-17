#include "caption_core.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <memory>
#include <stdexcept>

namespace caption {
static uint32_t u32(const unsigned char * b) { return b[0] | uint32_t(b[1])<<8 | uint32_t(b[2])<<16 | uint32_t(b[3])<<24; }
static uint16_t u16(const unsigned char * b) { return b[0] | uint16_t(b[1])<<8; }
std::vector<float> read_pcm16_wav(const std::string & path) {
    std::ifstream f(path, std::ios::binary);
    char riff[12];
    if (!f.read(riff, 12) || std::string(riff,4)!="RIFF" || std::string(riff+8,4)!="WAVE") throw std::runtime_error("Invalid WAV header");
    bool format_ok=false;
    while (f) {
        unsigned char header[8];
        if (!f.read(reinterpret_cast<char *>(header),8)) break;
        auto size=u32(header+4);
        auto id=std::string(reinterpret_cast<char *>(header),4);
        if (id=="fmt ") {
            unsigned char fmt[16];
            if (size<16 || !f.read(reinterpret_cast<char *>(fmt),16)) throw std::runtime_error("Truncated WAV format");
            format_ok=u16(fmt)==1 && u16(fmt+2)==1 && u32(fmt+4)==16000 && u16(fmt+12)==2 && u16(fmt+14)==16;
            f.seekg(size-16+(size&1),std::ios::cur);
        } else if (id=="data") {
            if (!format_ok || size%2) throw std::runtime_error("Expected PCM16 mono 16 kHz WAV");
            std::vector<float> samples;
            samples.reserve(std::min<uint32_t>(size/2,16000*60));
            unsigned char buffer[8192];
            uint32_t remaining=size;
            while (remaining) {
                auto n=std::min<uint32_t>(remaining,sizeof(buffer));
                if (!f.read(reinterpret_cast<char *>(buffer),n)) throw std::runtime_error("Truncated WAV samples");
                for (uint32_t i=0;i<n;i+=2) samples.push_back(static_cast<int16_t>(u16(buffer+i))/32768.0f);
                remaining-=n;
            }
            if (samples.empty()) throw std::runtime_error("Empty audio");
            return samples;
        } else f.seekg(size+(size&1),std::ios::cur);
    }
    throw std::runtime_error("Missing WAV data");
}
std::string escape(const std::string & value) {
    std::string out;
    const char * hex="0123456789abcdef";
    for (unsigned char c:value) {
        if(c=='"'||c=='\\') { out+='\\'; out+=c; }
        else if(c<32) { out+="\\u00"; out+=hex[c>>4]; out+=hex[c&15]; }
        else out+=c;
    }
    return out;
}
whisper_full_params parameters(const char * language, int threads, const char * vad_path, bool fast_decode) {
    auto p=whisper_full_default_params(fast_decode ? WHISPER_SAMPLING_GREEDY : WHISPER_SAMPLING_BEAM_SEARCH);
    p.language=language;
    p.detect_language=false;
    p.translate=false;
    p.n_threads=std::clamp(threads,1,8);
    p.no_context=true;
    p.n_max_text_ctx=0;
    p.single_segment=false;
    p.no_timestamps=false;
    p.print_progress=false;
    p.print_realtime=false;
    p.print_timestamps=false;
    p.token_timestamps=true;
    p.max_len=80;
    p.split_on_word=true;
    p.suppress_blank=true;
    p.suppress_nst=true;
    p.temperature=0.0f;
    p.temperature_inc=0.2f;
    p.greedy.best_of=fast_decode?1:5;
    p.beam_search.beam_size=5;
#if defined(CAPTION_BENCHMARK_BEAM_SIZE) && !defined(__ANDROID__)
    p.beam_search.beam_size=CAPTION_BENCHMARK_BEAM_SIZE;
#endif
    p.greedy.best_of=5;
    p.no_speech_thold=0.6f;
    p.logprob_thold=-1.0f;
    p.vad=vad_path && vad_path[0];
    p.vad_model_path=vad_path;
    p.vad_params=whisper_vad_default_params();
    p.vad_params.threshold=0.5f;
    p.vad_params.min_speech_duration_ms=250;
    p.vad_params.min_silence_duration_ms=500;
    p.vad_params.speech_pad_ms=200;
    return p;
}
Engine * load(const std::string & path,int mels,int decoder_layers) {
    auto p=whisper_context_default_params();
    p.use_gpu=false;
    p.flash_attn=true;
    auto * ctx=whisper_init_from_file_with_params(path.c_str(),p);
    if(!ctx) throw std::runtime_error("Model could not be loaded");
    if(whisper_model_n_mels(ctx)!=mels || whisper_model_n_text_layer(ctx)!=decoder_layers) {
        whisper_free(ctx);
        throw std::runtime_error("Model architecture does not match the selected model");
    }
    return new Engine{ctx};
}
static bool abort_callback(void * data) { return static_cast<Engine *>(data)->cancelled.load(); }
bool has_confident_speech(const float *probabilities,int count,int first_sample,int last_sample) {
    // Silero v5.1.2 uses 512-sample (32 ms) frames at 16 kHz. A confident
    // 256 ms speech seed prevents brief instrumental peaks from becoming words.
    // Keep the existing low-threshold boundaries/padding around a valid seed;
    // never trim phonemes, join audio, or inspect/replace recognized words.
    if(!probabilities || count<=0 || last_sample<=first_sample) return false;
    const int first=std::clamp((first_sample+511)/512,0,count);
    const int last=std::clamp(last_sample/512,first,count);
    int consecutive=0;
    for(int i=first;i<last;++i) {
        consecutive=probabilities[i]>=0.9f?consecutive+1:0;
        if(consecutive>=8) return true;
    }
    return false;
}

std::vector<std::pair<int,int>> coalesce_speech_windows(
    const std::vector<std::pair<int,int>> & input,
    int max_gap_samples,
    int max_window_samples) {
    std::vector<std::pair<int,int>> out;
    for(const auto & window:input) {
        if(window.second<=window.first) continue;
        if(!out.empty()) {
            auto & previous=out.back();
            const int gap=window.first-previous.second;
            const int combined=window.second-previous.first;
            if(gap>=0 && gap<=max_gap_samples && combined<=max_window_samples) {
                previous.second=window.second;
                continue;
            }
        }
        out.push_back(window);
    }
    return out;
}
std::string transcribe(Engine & engine,const std::string & path,const std::string & vad,const std::string & language,int threads,bool fast_decode) {
    if(language!="fa" && language!="ar") throw std::runtime_error("Explicit fa/ar language required");
    engine.cancelled=false;
    auto audio=read_pcm16_wav(path);
    if(engine.cancelled) throw std::runtime_error("Cancelled");
    const double duration=audio.size()/16000.0;
    double energy=0;
    for(auto x:audio) energy+=x*x;
    if(energy/audio.size()<1e-10) return "{\"duration\":"+std::to_string(duration)+",\"segments\":[]}";
    // Upstream VAD remaps segment times but not token times. Preserve original
    // intervals externally, then add the offset to BOTH words and segments.
    auto vp=whisper_vad_default_context_params(); vp.n_threads=std::clamp(threads,1,4); vp.use_gpu=false;
    std::unique_ptr<whisper_vad_context,decltype(&whisper_vad_free)> vctx(
        whisper_vad_init_from_file_with_params(vad.c_str(),vp),whisper_vad_free);
    if(!vctx) throw std::runtime_error("VAD model could not be loaded");
    auto p=parameters(language.c_str(),threads,vad.c_str(),fast_decode);
    auto vad_params=p.vad_params;
    vad_params.max_speech_duration_s=28.0f;
    std::unique_ptr<whisper_vad_segments,decltype(&whisper_vad_free_segments)> ranges(
        whisper_vad_segments_from_samples(vctx.get(),vad_params,audio.data(),static_cast<int>(audio.size())),whisper_vad_free_segments);
    if(!ranges) throw std::runtime_error("Voice activity detection failed");
    p.vad=false;
    p.vad_model_path=nullptr;
    p.abort_callback=abort_callback;
    p.abort_callback_user_data=&engine;
    std::string segments;
    std::vector<std::pair<int,int>> windows;
    for(int r=0;r<whisper_vad_segments_n_segments(ranges.get());++r) {
        const int first=std::clamp(static_cast<int>(std::lround(whisper_vad_segments_get_segment_t0(ranges.get(),r)*160)),0,static_cast<int>(audio.size()));
        const int last=std::clamp(static_cast<int>(std::lround(whisper_vad_segments_get_segment_t1(ranges.get(),r)*160)),first,static_cast<int>(audio.size()));
        if(last<=first) continue;
        if(language=="fa" && !has_confident_speech(whisper_vad_probs(vctx.get()),whisper_vad_n_probs(vctx.get()),first,last)) continue;
        windows.emplace_back(first,last);
    }
    // Persian conversational speech is often split into many short VAD ranges.
    // Running Whisper separately for each range repeats encoder/setup work.
    // Keep the original audio and timestamps, but batch neighbouring ranges
    // across pauses up to 1 second while preserving the existing 28-second cap.
    if(language=="fa") windows=coalesce_speech_windows(windows,16000,28*16000);
    for(const auto & window:windows) {
        const int first=window.first,last=window.second;
        const int64_t offset=first/16, limit=last/16;
        if(engine.cancelled) throw std::runtime_error("Cancelled");
        if(whisper_full(engine.ctx,p,audio.data()+first,last-first)!=0) throw std::runtime_error("Native transcription failed or cancelled");
        if(engine.cancelled) throw std::runtime_error("Cancelled");
        for(int i=0;i<whisper_full_n_segments(engine.ctx);++i) {
            if(!segments.empty()) segments+=",";
            auto start=std::clamp<int64_t>(offset+whisper_full_get_segment_t0(engine.ctx,i)*10,offset,limit);
            auto end=std::clamp<int64_t>(offset+whisper_full_get_segment_t1(engine.ctx,i)*10,start,limit);
            std::string words,word;
            int64_t word_start=-1,word_end=-1;
            auto flush=[&]() {
                if(word.empty()) return;
                if(!words.empty()) words+=",";
                words+="{\"text\":\""+escape(word)+"\",\"startMs\":"+std::to_string(word_start)+",\"endMs\":"+std::to_string(word_end)+"}";
                word.clear(); word_start=-1; word_end=-1;
            };
            for(int j=0;j<whisper_full_n_tokens(engine.ctx,i);++j) {
                auto td=whisper_full_get_token_data(engine.ctx,i,j);
                if(td.id>=whisper_token_eot(engine.ctx)) continue;
                std::string piece=whisper_full_get_token_text(engine.ctx,i,j);
                for(unsigned char c:piece) {
                    if(c==' '||c=='\n'||c=='\t'||c=='\r') flush();
                    else {
                        if(word.empty()) word_start=td.t0<0?-1:std::clamp<int64_t>(offset+td.t0*10,start,end);
                        word+=c;
                        word_end=td.t1<0?-1:std::clamp<int64_t>(offset+td.t1*10,start,end);
                    }
                }
            }
            flush();
            segments+="{\"startMs\":"+std::to_string(start)+",\"endMs\":"+std::to_string(end)+
                ",\"noSpeechProbability\":"+std::to_string(whisper_full_get_segment_no_speech_prob(engine.ctx,i))+
                ",\"text\":\""+escape(whisper_full_get_segment_text(engine.ctx,i))+"\",\"words\":["+words+"]}";
        }
    }
    return "{\"duration\":"+std::to_string(duration)+",\"language\":\""+language+"\",\"melBins\":"+
        std::to_string(whisper_model_n_mels(engine.ctx))+",\"decoderLayers\":"+std::to_string(whisper_model_n_text_layer(engine.ctx))+
        ",\"segments\":["+segments+"]}";
}
void release(Engine * engine) { if(engine) { whisper_free(engine->ctx); delete engine; } }
}
