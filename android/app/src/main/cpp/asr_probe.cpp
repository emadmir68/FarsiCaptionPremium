#include "caption_core.h"
#include <iostream>
int main(int argc,char **argv) {
    if(argc!=7) { std::cerr<<"model wav vad fa|ar mel_bins decoder_layers\n"; return 2; }
    caption::Engine *e=nullptr;
    try {
        e=caption::load(argv[1],std::stoi(argv[5]),std::stoi(argv[6]));
        std::cout<<caption::transcribe(*e,argv[2],argv[3],argv[4],4)<<std::endl;
        caption::release(e); return 0;
    } catch(const std::exception &ex) { caption::release(e); std::cerr<<ex.what()<<std::endl; return 1; }
}
