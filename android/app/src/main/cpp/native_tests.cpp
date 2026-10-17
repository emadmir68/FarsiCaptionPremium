#include "caption_core.h"
#include <fstream>
#include <iostream>
#include <stdexcept>
static void check(bool ok,const char *message) { if(!ok) throw std::runtime_error(message); }
static void le(std::ofstream &f,unsigned n,int bytes) { for(int i=0;i<bytes;++i) f.put((n>>(8*i))&255); }
int main() {
    try {
        auto p=caption::parameters("fa",12,"vad.bin");
        check(std::string(p.language)=="fa" && !p.detect_language && !p.translate,"language/task isolation");
        check(p.no_context && p.n_max_text_ctx==0,"context reset");
        check(p.token_timestamps && p.split_on_word && p.max_len==80,"timestamp settings reach native");
        check(p.vad && p.suppress_nst && p.suppress_blank && p.n_threads==8,"VAD and threading settings");
        check(p.strategy==WHISPER_SAMPLING_BEAM_SEARCH && p.beam_search.beam_size==5,"beam configuration");
        auto fast=caption::parameters("fa",8,"vad.bin",true);
        check(fast.strategy==WHISPER_SAMPLING_GREEDY && fast.greedy.best_of==1,"fast mode greedy configuration");
        check(std::string(caption::parameters("ar",1,"vad.bin").language)=="ar","Arabic language isolation");
        std::vector<float> quiet(20,0.1f);
        check(!caption::has_confident_speech(quiet.data(),quiet.size(),0,20*512),"quiet audio has no speech seed");
        quiet[5]=0.99f;
        check(!caption::has_confident_speech(quiet.data(),quiet.size(),0,20*512),"isolated music peak is not speech");
        for(int i=5;i<13;++i) quiet[i]=0.99f;
        check(caption::has_confident_speech(quiet.data(),quiet.size(),0,20*512),"short confident speech survives");
        check(!caption::has_confident_speech(quiet.data(),quiet.size(),0,12*512),"a later window cannot lend a speech seed");
        quiet[8]=0.2f;
        check(!caption::has_confident_speech(quiet.data(),quiet.size(),0,20*512),"separated peaks are not continuous speech");
        check(!caption::has_confident_speech(nullptr,0,0,512),"missing probabilities fail closed");
        std::vector<std::pair<int,int>> windows{{0,4*16000},{4*16000+8000,8*16000},{10*16000,12*16000},{35*16000,40*16000}};
        auto merged=caption::coalesce_speech_windows(windows,16000,28*16000);
        check(merged.size()==3 && merged[0].first==0 && merged[0].second==8*16000,"nearby VAD windows batch together");
        check(merged[1].first==10*16000 && merged[1].second==12*16000,"gap over one second stays separate");
        check(merged[2].first==35*16000 && merged[2].second==40*16000,"distant speech stays separate");
        std::ofstream f("native-tail.wav",std::ios::binary);
        f.write("RIFF",4); le(f,36+10+2*4103,4); f.write("WAVEJUNK",8); le(f,1,4); f.put('x'); f.put(0);
        f.write("fmt ",4); le(f,16,4); le(f,1,2); le(f,1,2); le(f,16000,4); le(f,32000,4); le(f,2,2); le(f,16,2);
        f.write("data",4); le(f,2*4103,4); for(int i=0;i<4103;++i) le(f,i==4102?16384:0,2); f.close();
        auto audio=caption::read_pcm16_wav("native-tail.wav");
        check(audio.size()==4103 && audio.back()==0.5f,"last partial block must survive");
        std::remove("native-tail.wav");
        check(caption::escape("سلام\n\t\"\\")=="سلام\\u000a\\u0009\\\"\\\\","UTF-8 and JSON escaping");
        std::cout<<"Native configuration, language, PCM tail and UTF-8 tests passed\n"; return 0;
    } catch(const std::exception &e) { std::cerr<<e.what()<<std::endl; return 1; }
}
