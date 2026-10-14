#include "caption_core.h"
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
int main(int argc,char **argv) {
    if(argc!=7) { std::cerr<<"model vad input.tsv output_dir mel_bins decoder_layers\n"; return 2; }
    caption::Engine *engine=nullptr;
    try {
        engine=caption::load(argv[1],std::stoi(argv[5]),std::stoi(argv[6]));
        std::filesystem::create_directories(argv[4]);
        std::ifstream input(argv[3]);if(!input) throw std::runtime_error("Missing input list");
        std::string line;
        while(std::getline(input,line)) {
            auto split=line.find('\t');if(split==std::string::npos) throw std::runtime_error("Expected ID TAB WAV");
            auto id=line.substr(0,split);if(id.find_first_not_of("abcdefghijklmnopqrstuvwxyz0123456789-_")!=std::string::npos) throw std::runtime_error("Invalid sample ID");
            auto start=std::chrono::steady_clock::now();
            auto json=caption::transcribe(*engine,line.substr(split+1),argv[2],"fa",4);
            double elapsed=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
            std::ofstream out(std::filesystem::path(argv[4])/(id+".json"));out<<json<<std::endl;
            std::ofstream timing(std::filesystem::path(argv[4])/(id+".seconds"));timing<<elapsed<<std::endl;
            std::cout<<id<<" "<<elapsed<<" s"<<std::endl;
        }
        caption::release(engine);return 0;
    }catch(const std::exception &e){caption::release(engine);std::cerr<<e.what()<<std::endl;return 1;}
}
