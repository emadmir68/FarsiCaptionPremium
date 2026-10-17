#include "caption_core.h"
#include <jni.h>
#include <stdexcept>
static std::string str(JNIEnv *env,jstring value) {
    const char *p=env->GetStringUTFChars(value,nullptr);
    std::string result=p?p:"";
    if(p) env->ReleaseStringUTFChars(value,p);
    return result;
}
static void error(JNIEnv *env,const char *message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),message); }
extern "C" JNIEXPORT jlong JNICALL Java_com_farsicaption_premium_NativeWhisper_load(JNIEnv *env,jobject,jstring path,jint mels,jint layers) {
    try { return reinterpret_cast<jlong>(caption::load(str(env,path),mels,layers)); }
    catch(const std::exception &e) { error(env,e.what()); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_farsicaption_premium_NativeWhisper_transcribe(JNIEnv *env,jobject,jlong handle,jstring path,jstring vad,jstring language,jint threads,jboolean fastDecode) {
    try {
        if(!handle) throw std::runtime_error("Invalid model handle");
        auto result=caption::transcribe(*reinterpret_cast<caption::Engine *>(handle),str(env,path),str(env,vad),str(env,language),threads,fastDecode==JNI_TRUE);
        auto out=env->NewByteArray(result.size());
        env->SetByteArrayRegion(out,0,result.size(),reinterpret_cast<const jbyte *>(result.data()));
        return out;
    } catch(const std::exception &e) { error(env,e.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_farsicaption_premium_NativeWhisper_cancel(JNIEnv *,jobject,jlong handle) {
    if(handle) reinterpret_cast<caption::Engine *>(handle)->cancelled=true;
}
extern "C" JNIEXPORT void JNICALL Java_com_farsicaption_premium_NativeWhisper_release(JNIEnv *,jobject,jlong handle) { caption::release(reinterpret_cast<caption::Engine *>(handle)); }
