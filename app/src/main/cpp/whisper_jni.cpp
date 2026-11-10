#include <jni.h>
#include <algorithm>
#include <string>
#include <vector>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_novaflow_cueflow_WhisperEngine_open(JNIEnv *env, jobject, jstring path) {
    const char *filename = env->GetStringUTFChars(path, nullptr);
    if (!filename) return 0;
    auto options = whisper_context_default_params();
    options.use_gpu = false;
    auto *context = whisper_init_from_file_with_params(filename, options);
    env->ReleaseStringUTFChars(path, filename);
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_novaflow_cueflow_WhisperEngine_decode(JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jint threads) {
    auto *context = reinterpret_cast<whisper_context *>(handle);
    const auto length = env->GetArrayLength(pcm);
    std::vector<float> samples(length);
    env->GetFloatArrayRegion(pcm, 0, length, samples.data());
    if (env->ExceptionCheck()) return nullptr;

    auto options = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    options.language = "en";
    options.n_threads = std::max(1, std::min(static_cast<int>(threads), 8));
    options.no_context = true;
    options.no_timestamps = true;
    options.single_segment = true;
    // Three-second live windows need only a short transcript, even if decoding repeats.
    options.max_tokens = 64;
    // The app supplies at most three seconds. Limit the encoder to five seconds of context.
    options.audio_ctx = 250;
    options.temperature_inc = 0.0f;
    options.greedy.best_of = 1;
    options.print_progress = false;
    options.print_realtime = false;
    options.print_timestamps = false;
    options.print_special = false;
    if (whisper_full(context, options, samples.data(), length) != 0) return nullptr;

    std::string text;
    for (int i = 0; i < whisper_full_n_segments(context); ++i) {
        text += whisper_full_get_segment_text(context, i);
    }
    auto result = env->NewByteArray(static_cast<jsize>(text.size()));
    if (result && !text.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()),
                               reinterpret_cast<const jbyte *>(text.data()));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_novaflow_cueflow_WhisperEngine_close(JNIEnv *, jobject, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}
