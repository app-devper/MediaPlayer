/*
 * JNI bridge for com.appdevper.mediaplayer.airplay.NativeAirPlay (Android and Desktop).
 * Copyright (C) 2026 appdevper. GPL-3.0-or-later (links GPL-3.0 UxPlay code).
 *
 * UxPlay calls back on its own network threads; they are attached to the JVM on first use and
 * detached when the thread exits.
 */
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "airplay_core.h"

static JavaVM *jvm = NULL;
static jobject listener = NULL; /* global ref while running */
static pthread_key_t detach_key;

static struct {
    jmethodID log, connections, admit, show_pin, register_client, is_registered;
    jmethodID video_format, video, video_stop, audio_format, audio, audio_flush, audio_volume, reset;
    jmethodID video_play, video_scrub, video_rate, video_playback_stop, video_info;
} m;

static void detach_thread(void *unused) {
    (void) unused;
    if (jvm) (*jvm)->DetachCurrentThread(jvm);
}

static JNIEnv *env_for_thread(void) {
    JNIEnv *env = NULL;
    if ((*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
#ifdef __ANDROID__
    if ((*jvm)->AttachCurrentThreadAsDaemon(jvm, &env, NULL) != JNI_OK) return NULL;
#else
    if ((*jvm)->AttachCurrentThreadAsDaemon(jvm, (void **) &env, NULL) != JNI_OK) return NULL;
#endif
    pthread_setspecific(detach_key, (void *) 1);
    return env;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    jvm = vm;
    pthread_key_create(&detach_key, detach_thread);
    return JNI_VERSION_1_6;
}

/* Exceptions thrown by the listener must not leak into UxPlay's threads. */
static void clear_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static jstring jstr(JNIEnv *env, const char *s) {
    return (*env)->NewStringUTF(env, s ? s : "");
}

/* Variadic so the body may contain commas. */
#define WITH_ENV(...) do { \
    JNIEnv *env = env_for_thread(); \
    jobject l = listener; \
    if (!env || !l) break; \
    __VA_ARGS__; \
    clear_exception(env); \
} while (0)

static void cb_log(void *ctx, int level, const char *msg) {
    (void) ctx;
    WITH_ENV({
        jstring s = jstr(env, msg);
        (*env)->CallVoidMethod(env, l, m.log, (jint) level, s);
        (*env)->DeleteLocalRef(env, s);
    });
}

static void cb_connections(void *ctx, int open) {
    (void) ctx;
    WITH_ENV((*env)->CallVoidMethod(env, l, m.connections, (jint) open));
}

static bool cb_admit(void *ctx, const char *device_id, const char *model, const char *name) {
    (void) ctx;
    bool admit = false;
    WITH_ENV({
        jstring a = jstr(env, device_id), b = jstr(env, model), c = jstr(env, name);
        admit = (*env)->CallBooleanMethod(env, l, m.admit, a, b, c);
        if ((*env)->ExceptionCheck(env)) admit = false;
        (*env)->DeleteLocalRef(env, a); (*env)->DeleteLocalRef(env, b); (*env)->DeleteLocalRef(env, c);
    });
    return admit;
}

static void cb_show_pin(void *ctx, const char *pin) {
    (void) ctx;
    WITH_ENV({
        jstring s = jstr(env, pin);
        (*env)->CallVoidMethod(env, l, m.show_pin, s);
        (*env)->DeleteLocalRef(env, s);
    });
}

static void cb_register_client(void *ctx, const char *device_id, const char *pk, const char *name) {
    (void) ctx;
    WITH_ENV({
        jstring a = jstr(env, device_id), b = jstr(env, pk), c = jstr(env, name);
        (*env)->CallVoidMethod(env, l, m.register_client, a, b, c);
        (*env)->DeleteLocalRef(env, a); (*env)->DeleteLocalRef(env, b); (*env)->DeleteLocalRef(env, c);
    });
}

static bool cb_is_registered(void *ctx, const char *pk) {
    (void) ctx;
    bool registered = false;
    WITH_ENV({
        jstring s = jstr(env, pk);
        registered = (*env)->CallBooleanMethod(env, l, m.is_registered, s);
        if ((*env)->ExceptionCheck(env)) registered = false;
        (*env)->DeleteLocalRef(env, s);
    });
    return registered;
}

static void cb_video_format(void *ctx, bool h265, int width, int height) {
    (void) ctx;
    WITH_ENV((*env)->CallVoidMethod(env, l, m.video_format, (jboolean) h265, (jint) width, (jint) height));
}

static void call_with_bytes(jmethodID method, const uint8_t *data, int len, uint64_t pts) {
    WITH_ENV({
        jbyteArray a = (*env)->NewByteArray(env, len);
        if (!a) { clear_exception(env); break; }
        (*env)->SetByteArrayRegion(env, a, 0, len, (const jbyte *) data);
        (*env)->CallVoidMethod(env, l, method, a, (jlong) pts);
        (*env)->DeleteLocalRef(env, a);
    });
}

static void cb_video(void *ctx, const uint8_t *data, int len, uint64_t pts) {
    (void) ctx;
    call_with_bytes(m.video, data, len, pts);
}

static void cb_video_stop(void *ctx) {
    (void) ctx;
    WITH_ENV((*env)->CallVoidMethod(env, l, m.video_stop));
}

static void cb_audio_format(void *ctx, int ct, int spf, bool using_screen) {
    (void) ctx;
    WITH_ENV((*env)->CallVoidMethod(env, l, m.audio_format, (jint) ct, (jint) spf, (jboolean) using_screen));
}

static void cb_audio(void *ctx, const uint8_t *data, int len, uint64_t pts) {
    (void) ctx;
    call_with_bytes(m.audio, data, len, pts);
}

static void cb_audio_flush(void *ctx) {
    (void) ctx;
    WITH_ENV((*env)->CallVoidMethod(env, l, m.audio_flush));
}

static void cb_audio_volume(void *ctx, float db) {
    (void) ctx;
    WITH_ENV((*env)->CallVoidMethod(env, l, m.audio_volume, (jfloat) db));
}

static void cb_reset(void *ctx, int reason) {
    (void) ctx;
    WITH_ENV((*env)->CallVoidMethod(env, l, m.reset, (jint) reason));
}

static void cb_video_play(void *ctx, const char *url, float start) {
    (void) ctx;
    WITH_ENV({
        jstring s = jstr(env, url);
        (*env)->CallVoidMethod(env, l, m.video_play, s, (jfloat) start);
        (*env)->DeleteLocalRef(env, s);
    });
}

static void cb_video_scrub(void *ctx, float seconds) {
    (void) ctx;
    WITH_ENV((*env)->CallVoidMethod(env, l, m.video_scrub, (jfloat) seconds));
}

static void cb_video_rate(void *ctx, float rate) {
    (void) ctx;
    WITH_ENV((*env)->CallVoidMethod(env, l, m.video_rate, (jfloat) rate));
}

static void cb_video_playback_stop(void *ctx) {
    (void) ctx;
    WITH_ENV((*env)->CallVoidMethod(env, l, m.video_playback_stop));
}

/* Kotlin returns [duration, position, rate] or null while nothing is loaded. */
static bool cb_video_info(void *ctx, double *duration, double *position, float *rate) {
    (void) ctx;
    bool loaded = false;
    WITH_ENV({
        jdoubleArray a = (jdoubleArray) (*env)->CallObjectMethod(env, l, m.video_info);
        if (!(*env)->ExceptionCheck(env) && a && (*env)->GetArrayLength(env, a) == 3) {
            jdouble v[3];
            (*env)->GetDoubleArrayRegion(env, a, 0, 3, v);
            *duration = v[0];
            *position = v[1];
            *rate = (float) v[2];
            loaded = true;
        }
        if (a) (*env)->DeleteLocalRef(env, a);
    });
    return loaded;
}

static bool lookup_methods(JNIEnv *env, jobject obj) {
    jclass c = (*env)->GetObjectClass(env, obj);
#define M(field, name, sig) m.field = (*env)->GetMethodID(env, c, name, sig); if (!m.field) return false;
    M(log, "onLog", "(ILjava/lang/String;)V")
    M(connections, "onConnections", "(I)V")
    M(admit, "admit", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z")
    M(show_pin, "onShowPin", "(Ljava/lang/String;)V")
    M(register_client, "onRegisterClient", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")
    M(is_registered, "isRegistered", "(Ljava/lang/String;)Z")
    M(video_format, "onVideoFormat", "(ZII)V")
    M(video, "onVideo", "([BJ)V")
    M(video_stop, "onVideoStop", "()V")
    M(audio_format, "onAudioFormat", "(IIZ)V")
    M(audio, "onAudio", "([BJ)V")
    M(audio_flush, "onAudioFlush", "()V")
    M(audio_volume, "onVolume", "(F)V")
    M(reset, "onReset", "(I)V")
    M(video_play, "onVideoPlay", "(Ljava/lang/String;F)V")
    M(video_scrub, "onVideoScrub", "(F)V")
    M(video_rate, "onVideoRate", "(F)V")
    M(video_playback_stop, "onVideoPlaybackStop", "()V")
    M(video_info, "videoInfo", "()[D")
#undef M
    (*env)->DeleteLocalRef(env, c);
    return true;
}

JNIEXPORT jint JNICALL
Java_com_appdevper_mediaplayer_airplay_NativeAirPlay_start(JNIEnv *env, jobject thiz, jstring name, jbyteArray hw_addr,
                                                           jstring keyfile, jint width, jint height, jint fps,
                                                           jboolean require_pin, jboolean video, jobject callbacks) {
    (void) thiz;
    ap_stop();
    if (listener) {
        (*env)->DeleteGlobalRef(env, listener);
        listener = NULL;
    }
    if ((*env)->GetArrayLength(env, hw_addr) != 6) return -100;
    if (!lookup_methods(env, callbacks)) {
        clear_exception(env);
        return -101;
    }
    listener = (*env)->NewGlobalRef(env, callbacks);

    ap_config_t config;
    memset(&config, 0, sizeof(config));
    const char *name_c = (*env)->GetStringUTFChars(env, name, NULL);
    const char *keyfile_c = (*env)->GetStringUTFChars(env, keyfile, NULL);
    (*env)->GetByteArrayRegion(env, hw_addr, 0, 6, (jbyte *) config.hw_addr);
    config.name = name_c;
    config.keyfile = keyfile_c;
    config.width = width;
    config.height = height;
    config.fps = fps;
    config.require_pin = require_pin;
    config.video = video;

    ap_callbacks_t cb = {
        .log = cb_log, .connections = cb_connections, .admit = cb_admit, .show_pin = cb_show_pin,
        .register_client = cb_register_client, .is_registered = cb_is_registered,
        .video_format = cb_video_format, .video = cb_video, .video_stop = cb_video_stop,
        .audio_format = cb_audio_format, .audio = cb_audio, .audio_flush = cb_audio_flush,
        .audio_volume = cb_audio_volume, .reset = cb_reset,
        .video_play = cb_video_play, .video_scrub = cb_video_scrub, .video_rate = cb_video_rate,
        .video_playback_stop = cb_video_playback_stop, .video_info = cb_video_info,
    };
    int result = ap_start(&config, &cb);
    (*env)->ReleaseStringUTFChars(env, name, name_c);
    (*env)->ReleaseStringUTFChars(env, keyfile, keyfile_c);
    if (result != 0) {
        (*env)->DeleteGlobalRef(env, listener);
        listener = NULL;
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_appdevper_mediaplayer_airplay_NativeAirPlay_port(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return ap_port();
}

JNIEXPORT void JNICALL
Java_com_appdevper_mediaplayer_airplay_NativeAirPlay_stop(JNIEnv *env, jobject thiz) {
    (void) thiz;
    ap_stop(); /* joins UxPlay's threads, so no callback runs after this */
    if (listener) {
        (*env)->DeleteGlobalRef(env, listener);
        listener = NULL;
    }
}
