// JNI bridge between WhisperNative.kt and whisper.cpp. Modelled on whisper.cpp's Android example,
// with what this app needs on top: live segments and progress, cancellation, and the per-segment
// probabilities the cloud fallback is calibrated on.

#include <jni.h>
#include <android/log.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "whisper.h"

#define TAG "WhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// Must match WhisperNative.ABORTED.
#define RESULT_ABORTED (-1000)

// One loaded model, plus the flag that lets Kotlin stop a transcription in progress.
struct engine {
    struct whisper_context * ctx;
    atomic_bool abort;
};

// What the whisper callbacks need to reach back into Kotlin. They run on the thread that called
// transcribe(), so its JNIEnv is valid for them.
struct callback_target {
    JNIEnv * env;
    jobject listener;
    jmethodID on_progress;
    jmethodID on_segment;
    struct engine * engine;
};

static struct engine * from_handle(jlong handle) {
    return (struct engine *) (intptr_t) handle;
}

// Text goes to Kotlin as UTF-8 bytes, never through NewStringUTF: Whisper can split a multi-byte
// character across tokens, and malformed input to NewStringUTF aborts a debuggable app.
static jbyteArray utf8_bytes(JNIEnv * env, const char * text) {
    const jsize n = (jsize) strlen(text);
    jbyteArray bytes = (*env)->NewByteArray(env, n);
    if (bytes != NULL) (*env)->SetByteArrayRegion(env, bytes, 0, n, (const jbyte *) text);
    return bytes;
}

// A Kotlin exception inside a callback would make every later JNI call invalid: stop the run.
static void abort_on_exception(struct callback_target * t) {
    if ((*t->env)->ExceptionCheck(t->env)) atomic_store(&t->engine->abort, true);
}

static void on_new_segment(struct whisper_context * ctx, struct whisper_state * state, int n_new, void * user_data) {
    (void) state;
    struct callback_target * t = user_data;
    if (atomic_load(&t->engine->abort)) return;
    const int n = whisper_full_n_segments(ctx);
    for (int i = n - n_new; i < n; i++) {
        jbyteArray text = utf8_bytes(t->env, whisper_full_get_segment_text(ctx, i));
        (*t->env)->CallVoidMethod(t->env, t->listener, t->on_segment, text);
        (*t->env)->DeleteLocalRef(t->env, text);
        abort_on_exception(t);
    }
}

static void on_progress(struct whisper_context * ctx, struct whisper_state * state, int progress, void * user_data) {
    (void) ctx;
    (void) state;
    struct callback_target * t = user_data;
    if (atomic_load(&t->engine->abort)) return;
    (*t->env)->CallVoidMethod(t->env, t->listener, t->on_progress, progress);
    abort_on_exception(t);
}

// whisper.cpp logs to stderr, which Android discards. Routed to logcat, its model-load report
// (detected CPU features, buffer sizes) and per-run timings are readable with
// `adb logcat -s whisper.cpp`.
static void log_to_logcat(enum ggml_log_level level, const char * text, void * user_data) {
    (void) user_data;
    int priority = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) priority = ANDROID_LOG_DEBUG;
    __android_log_print(priority, "whisper.cpp", "%s", text);
}

// Called by ggml during compute, possibly on its worker threads: touches nothing but the flag.
static bool should_abort(void * user_data) {
    return atomic_load(&((struct engine *) user_data)->abort);
}

JNIEXPORT jstring JNICALL
Java_dev_junjo_eztranscribe_engine_WhisperNative_systemInfo(JNIEnv * env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_dev_junjo_eztranscribe_engine_WhisperNative_load(JNIEnv * env, jobject thiz, jstring model_path) {
    (void) thiz;
    whisper_log_set(log_to_logcat, NULL);
    const char * path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    struct whisper_context * ctx = whisper_init_from_file_with_params(path, params);
    if (ctx == NULL) LOGW("could not load %s", path);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (ctx == NULL) return 0;

    struct engine * e = calloc(1, sizeof *e);
    if (e == NULL) {
        whisper_free(ctx);
        return 0;
    }
    e->ctx = ctx;
    atomic_init(&e->abort, false);
    return (jlong) (intptr_t) e;
}

JNIEXPORT void JNICALL
Java_dev_junjo_eztranscribe_engine_WhisperNative_free(JNIEnv * env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    struct engine * e = from_handle(handle);
    if (e == NULL) return;
    whisper_free(e->ctx);
    free(e);
}

JNIEXPORT void JNICALL
Java_dev_junjo_eztranscribe_engine_WhisperNative_requestAbort(JNIEnv * env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    struct engine * e = from_handle(handle);
    if (e != NULL) atomic_store(&e->abort, true);
}

JNIEXPORT jint JNICALL
Java_dev_junjo_eztranscribe_engine_WhisperNative_transcribe(
        JNIEnv * env, jobject thiz, jlong handle, jfloatArray pcm, jint threads,
        jstring language, jstring prompt, jstring vad_model_path, jobject listener) {
    (void) thiz;
    struct engine * e = from_handle(handle);
    atomic_store(&e->abort, false);

    const char * lang = (*env)->GetStringUTFChars(env, language, NULL);
    const char * prompt_text = prompt != NULL ? (*env)->GetStringUTFChars(env, prompt, NULL) : NULL;
    const char * vad = vad_model_path != NULL ? (*env)->GetStringUTFChars(env, vad_model_path, NULL) : NULL;

    // Greedy first, as the plan says; whisper.cpp's temperature fallback stays on, since it is
    // what breaks the repetition loops greedy decoding can fall into.
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.translate = false;
    params.language = lang;
    params.initial_prompt = prompt_text;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.vad = vad != NULL;
    params.vad_model_path = vad;
    params.vad_params = whisper_vad_default_params();

    jclass listener_class = (*env)->GetObjectClass(env, listener);
    struct callback_target target = {
        .env = env,
        .listener = listener,
        .on_progress = (*env)->GetMethodID(env, listener_class, "onProgress", "(I)V"),
        .on_segment = (*env)->GetMethodID(env, listener_class, "onSegment", "([B)V"),
        .engine = e,
    };
    params.progress_callback = on_progress;
    params.progress_callback_user_data = &target;
    params.new_segment_callback = on_new_segment;
    params.new_segment_callback_user_data = &target;
    params.abort_callback = should_abort;
    params.abort_callback_user_data = e;

    const jsize n_samples = (*env)->GetArrayLength(env, pcm);
    // Not GetPrimitiveArrayCritical: the callbacks call back into Java while this is held.
    jfloat * samples = (*env)->GetFloatArrayElements(env, pcm, NULL);
    whisper_reset_timings(e->ctx);
    int result = whisper_full(e->ctx, params, samples, n_samples);
    // Encoder, decoder and per-window costs, to logcat: where a slow run spent its time.
    whisper_print_timings(e->ctx);
    (*env)->ReleaseFloatArrayElements(env, pcm, samples, JNI_ABORT);

    (*env)->ReleaseStringUTFChars(env, language, lang);
    if (prompt_text != NULL) (*env)->ReleaseStringUTFChars(env, prompt, prompt_text);
    if (vad != NULL) (*env)->ReleaseStringUTFChars(env, vad_model_path, vad);

    if (atomic_load(&e->abort)) return RESULT_ABORTED;
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_junjo_eztranscribe_engine_WhisperNative_segmentCount(JNIEnv * env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    return whisper_full_n_segments(from_handle(handle)->ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_dev_junjo_eztranscribe_engine_WhisperNative_segmentText(JNIEnv * env, jobject thiz, jlong handle, jint index) {
    (void) thiz;
    return utf8_bytes(env, whisper_full_get_segment_text(from_handle(handle)->ctx, index));
}

JNIEXPORT jfloat JNICALL
Java_dev_junjo_eztranscribe_engine_WhisperNative_segmentNoSpeechProb(JNIEnv * env, jobject thiz, jlong handle, jint index) {
    (void) env;
    (void) thiz;
    return whisper_full_get_segment_no_speech_prob(from_handle(handle)->ctx, index);
}

// {sum of log-probabilities, number of tokens} over the segment's text tokens. Special tokens
// (timestamps, end of text and above) are left out, as in OpenAI's avg_logprob.
JNIEXPORT jfloatArray JNICALL
Java_dev_junjo_eztranscribe_engine_WhisperNative_segmentLogProbStats(JNIEnv * env, jobject thiz, jlong handle, jint index) {
    (void) thiz;
    struct whisper_context * ctx = from_handle(handle)->ctx;
    const whisper_token eot = whisper_token_eot(ctx);
    const int n_tokens = whisper_full_n_tokens(ctx, index);
    float sum = 0.0f;
    float count = 0.0f;
    for (int i = 0; i < n_tokens; i++) {
        const whisper_token_data token = whisper_full_get_token_data(ctx, index, i);
        if (token.id >= eot) continue;
        sum += token.plog;
        count += 1.0f;
    }
    jfloatArray stats = (*env)->NewFloatArray(env, 2);
    if (stats != NULL) {
        const jfloat values[2] = { sum, count };
        (*env)->SetFloatArrayRegion(env, stats, 0, 2, values);
    }
    return stats;
}
