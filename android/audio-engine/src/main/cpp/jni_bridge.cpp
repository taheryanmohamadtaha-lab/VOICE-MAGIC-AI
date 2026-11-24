#include <jni.h>
#include <vector>
#include "audio_engine.h"

using vm::AudioEngine;

#define JNI(name) Java_app_voicemagic_audio_NativeAudioEngine_##name

static AudioEngine* E(jlong h) { return reinterpret_cast<AudioEngine*>(h); }

extern "C" {

JNIEXPORT jlong JNICALL JNI(nativeCreate)(JNIEnv*, jobject) { return reinterpret_cast<jlong>(new AudioEngine()); }
JNIEXPORT void JNICALL JNI(nativeDestroy)(JNIEnv*, jobject, jlong h) { delete E(h); }

JNIEXPORT jboolean JNICALL JNI(nativeStart)(JNIEnv*, jobject, jlong h, jint in, jint out) {
    return E(h)->start(in, out) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL JNI(nativeStop)(JNIEnv*, jobject, jlong h) { E(h)->stop(); }

JNIEXPORT void JNICALL JNI(nativeSetParams)(JNIEnv* env, jobject, jlong h, jfloatArray arr) {
    if (env->GetArrayLength(arr) < vm::kParamCount) return;
    jfloat* p = env->GetFloatArrayElements(arr, nullptr);
    E(h)->chain().setParams(p);
    env->ReleaseFloatArrayElements(arr, p, JNI_ABORT);
}
JNIEXPORT void JNICALL JNI(nativeSetMute)(JNIEnv*, jobject, jlong h, jboolean m) { E(h)->setMuted(m); }
JNIEXPORT void JNICALL JNI(nativeSetMonitor)(JNIEnv*, jobject, jlong h, jboolean m) { E(h)->setMonitor(m); }
JNIEXPORT void JNICALL JNI(nativeSetRecording)(JNIEnv*, jobject, jlong h, jboolean r) { E(h)->setRecording(r); }
JNIEXPORT void JNICALL JNI(nativeResetClip)(JNIEnv*, jobject, jlong h) { E(h)->resetClip(); }

JNIEXPORT jint JNICALL JNI(nativeReadRecorded)(JNIEnv* env, jobject, jlong h, jfloatArray arr) {
    const jsize n = env->GetArrayLength(arr);
    jfloat* p = env->GetFloatArrayElements(arr, nullptr);
    const size_t got = E(h)->readRecorded(p, (size_t)n);
    env->ReleaseFloatArrayElements(arr, p, 0);
    return (jint)got;
}

JNIEXPORT void JNICALL JNI(nativeGetMeters)(JNIEnv* env, jobject, jlong h, jfloatArray arr) {
    if (env->GetArrayLength(arr) < 9) return;
    float m[9] = {0};
    E(h)->getMeters(m);
    env->SetFloatArrayRegion(arr, 0, 9, m);
}

// Offline rendering (Studio / Editor): same DSP chain, runs on the calling thread.
JNIEXPORT jfloatArray JNICALL JNI(nativeProcessOffline)(JNIEnv* env, jobject, jfloatArray in, jint sampleRate, jfloatArray params) {
    const jsize n = env->GetArrayLength(in);
    if (env->GetArrayLength(params) < vm::kParamCount || n <= 0) return env->NewFloatArray(0);
    std::vector<float> src((size_t)n), dst((size_t)n);
    env->GetFloatArrayRegion(in, 0, n, src.data());
    float p[vm::kParamCount];
    env->GetFloatArrayRegion(params, 0, vm::kParamCount, p);

    vm::EffectChain chain;
    chain.prepare((float)sampleRate);
    chain.setParams(p);
    const int B = 1024;
    for (jsize i = 0; i < n; i += B) chain.processBlock(src.data() + i, dst.data() + i, std::min<jsize>(B, n - i));

    jfloatArray out = env->NewFloatArray(n);
    env->SetFloatArrayRegion(out, 0, n, dst.data());
    return out;
}

}  // extern "C"
