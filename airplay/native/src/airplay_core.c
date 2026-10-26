/*
 * MediaPlayer AirPlay receiver: a small C API over UxPlay's raop/dnssd library.
 * Copyright (C) 2026 appdevper. GPL-3.0-or-later (links GPL-3.0 UxPlay code).
 *
 * Mirrors what UxPlay's uxplay.cpp does to start a receiver (start_dnssd + start_raop_server +
 * register_dnssd), minus the GStreamer renderers: decoded-ready data goes to ap_callbacks_t.
 */
#include "airplay_core.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "alac_decoder.h"
#include "dnssd.h"
#include "logger.h"
#include "raop.h"
#include "stream.h"

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static dnssd_t *dnssd = NULL;
static raop_t *raop = NULL;
static ap_callbacks_t cb;
static uint64_t remote_clock_offset = 0;
static int open_connections = 0;
static double current_volume_db = 0.0;

/* ALAC is decoded here so the app only ever sees PCM or AAC (Android has no ALAC decoder). */
static pthread_mutex_t audio_lock = PTHREAD_MUTEX_INITIALIZER;
static int audio_ct = 0;
static alac_decoder_t *alac = NULL;
static int16_t pcm[ALAC_FRAMES_PER_PACKET * ALAC_CHANNELS];

#define CALL(fn, ...) do { if (cb.fn) cb.fn(cb.ctx, __VA_ARGS__); } while (0)
#define CALL0(fn) do { if (cb.fn) cb.fn(cb.ctx); } while (0)

void ap_format_device_id(const uint8_t hw_addr[6], char *out) {
    snprintf(out, 18, "%02x:%02x:%02x:%02x:%02x:%02x",
             hw_addr[0], hw_addr[1], hw_addr[2], hw_addr[3], hw_addr[4], hw_addr[5]);
}

/* Remote NTP time → local clock, aligned on the first packet like uxplay.cpp does. */
static uint64_t local_pts(uint64_t ntp_time_local, uint64_t ntp_time_remote) {
    if (!remote_clock_offset) {
        uint64_t local_time = ntp_time_local ? ntp_time_local : get_local_time();
        remote_clock_offset = local_time - ntp_time_remote;
    }
    return ntp_time_remote + remote_clock_offset;
}

/* region raop callbacks */

static void on_log(void *cls, int level, const char *msg) {
    (void) cls;
    CALL(log, level, msg);
}

static void conn_init(void *cls) {
    (void) cls;
    CALL(connections, ++open_connections);
}

static void conn_destroy(void *cls) {
    (void) cls;
    if (open_connections > 0) open_connections--;
    if (open_connections == 0) remote_clock_offset = 0;
    CALL(connections, open_connections);
}

static void conn_reset(void *cls, int reason) {
    (void) cls;
    remote_clock_offset = 0;
    CALL(reset, reason);
}

static void conn_feedback(void *cls) { (void) cls; }

static void report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    (void) cls;
    *admit = cb.admit ? cb.admit(cb.ctx, deviceid, model, name) : true;
}

static void display_pin(void *cls, char *pin) {
    (void) cls;
    CALL(show_pin, pin);
}

static void register_client(void *cls, const char *device_id, const char *pk, const char *name) {
    (void) cls;
    CALL(register_client, device_id, pk, name);
}

static bool check_register(void *cls, const char *pk) {
    (void) cls;
    return cb.is_registered ? cb.is_registered(cb.ctx, pk) : true;
}

static int video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short width_source,
                                    unsigned short height_source, unsigned short x_offset,
                                    unsigned short y_offset, unsigned short width, unsigned short height) {
    (void) cls; (void) x_offset; (void) y_offset; (void) width; (void) height;
    CALL(video_format, codec == VIDEO_CODEC_H265, (int) width_source, (int) height_source);
    return 0;
}

static void video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) cls; (void) ntp;
    CALL(video, data->data, data->data_len, local_pts(data->ntp_time_local, data->ntp_time_remote));
}

static void video_reset(void *cls, reset_type_t type) {
    (void) cls;
    remote_clock_offset = 0;
    CALL0(video_stop);
    /* the sender ended AirPlay video, or another client took over */
    if (type == RESET_TYPE_HLS_SHUTDOWN || type == RESET_TYPE_HLS_EOS || type == RESET_TYPE_NOHOLD) CALL0(video_playback_stop);
}

static void video_flush(void *cls) { (void) cls; }
static void video_pause(void *cls) { (void) cls; }
static void video_resume(void *cls) { (void) cls; }

static void audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *using_screen,
                             bool *is_media, uint64_t *audio_format) {
    (void) cls; (void) is_media; (void) audio_format;
    pthread_mutex_lock(&audio_lock);
    audio_ct = *ct;
    if (audio_ct == AP_AUDIO_ALAC && !alac) alac = alac_decoder_create();
    bool decoded = audio_ct == AP_AUDIO_ALAC && alac;
    pthread_mutex_unlock(&audio_lock);
    if (decoded) {
        CALL(audio_format, AP_AUDIO_PCM, ALAC_FRAMES_PER_PACKET, *using_screen);
    } else {
        CALL(audio_format, (int) *ct, (int) *spf, *using_screen);
    }
}

static void audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) cls; (void) ntp;
    uint64_t pts = local_pts(data->ntp_time_local, data->ntp_time_remote);
    if (data->ct != AP_AUDIO_ALAC) {
        CALL(audio, data->data, data->data_len, pts);
        return;
    }
    pthread_mutex_lock(&audio_lock);
    int frames = alac ? alac_decoder_decode(alac, data->data, data->data_len, pcm, ALAC_FRAMES_PER_PACKET) : -1;
    if (frames > 0) CALL(audio, (const uint8_t *) pcm, frames * ALAC_CHANNELS * (int) sizeof(int16_t), pts);
    pthread_mutex_unlock(&audio_lock);
}

static void audio_flush(void *cls) {
    (void) cls;
    CALL0(audio_flush);
}

static void audio_set_volume(void *cls, float volume) {
    (void) cls;
    current_volume_db = volume;
    CALL(audio_volume, volume);
}

static double audio_set_client_volume(void *cls) {
    (void) cls;
    return current_volume_db;
}

/*
 * The rest are things we don't use yet (now-playing metadata, cover art, DACP remote control,
 * HLS video). UxPlay calls several of these without a NULL check, so every callback must be set:
 * a NULL here is a remote crash triggered by an ordinary client request.
 */
static void audio_set_metadata(void *cls, const void *buffer, int len) { (void) cls; (void) buffer; (void) len; }
static void audio_set_coverart(void *cls, const void *buffer, int len) { (void) cls; (void) buffer; (void) len; }
static void audio_stop_coverart_rendering(void *cls) { (void) cls; }
static void audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote) { (void) cls; (void) dacp_id; (void) active_remote; }
static void audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) { (void) cls; (void) start; (void) curr; (void) end; }
static void export_dacp(void *cls, const char *active_remote, const char *dacp_id) { (void) cls; (void) active_remote; (void) dacp_id; }
static void mirror_video_running(void *cls, bool running) { (void) cls; (void) running; }

/* AirPlay video: forwarded to the app's player. */
static void on_video_play(void *cls, const char *location, const float start) {
    (void) cls;
    CALL(video_play, location, start);
}

static void on_video_scrub(void *cls, const float position) {
    (void) cls;
    CALL(video_scrub, position);
}

static void on_video_rate(void *cls, const float rate) {
    (void) cls;
    CALL(video_rate, rate);
}

static void on_video_stop(void *cls) {
    (void) cls;
    CALL0(video_playback_stop);
}

static void on_video_acquire_playback_info(void *cls, playback_info_t *info) {
    (void) cls;
    memset(info, 0, sizeof(*info));
    double duration = 0, position = 0;
    float rate = 0;
    if (!cb.video_info || !cb.video_info(cb.ctx, &duration, &position, &rate)) {
        info->position = -1.0; /* "not available yet" to UxPlay */
        return;
    }
    info->duration = duration;
    info->position = position;
    info->rate = rate;
    info->seek_start = 0;
    info->seek_duration = duration > 0 ? duration : 0;
    info->ready_to_play = true;
    info->playback_likely_to_keep_up = true;
}

static float on_video_playlist_remove(void *cls) {
    (void) cls;
    double duration = 0, position = 0;
    float rate = 0;
    if (cb.video_info && cb.video_info(cb.ctx, &duration, &position, &rate)) return (float) position;
    return 0.0f;
}
static void get_custom_profile(void *cls, const char **profile) { (void) cls; *profile = NULL; }

/* endregion */

static void stop_locked(void) {
    if (raop) {
        raop_destroy(raop);
        raop = NULL;
    }
    if (dnssd) {
        dnssd_unregister_raop(dnssd);
        dnssd_unregister_airplay(dnssd);
        dnssd_destroy(dnssd);
        dnssd = NULL;
    }
    open_connections = 0;
    remote_clock_offset = 0;
    pthread_mutex_lock(&audio_lock);
    if (alac) alac_decoder_destroy(alac);
    alac = NULL;
    audio_ct = 0;
    pthread_mutex_unlock(&audio_lock);
}

int ap_start(const ap_config_t *config, const ap_callbacks_t *callbacks) {
    pthread_mutex_lock(&lock);
    stop_locked();
    cb = *callbacks;

    int error = 0;
    unsigned char pin_pw = config->require_pin ? 1 : 0;
    dnssd = dnssd_init(config->name, (int) strlen(config->name), (const char *) config->hw_addr, 6, pin_pw, &error);
    if (error || !dnssd) {
        dnssd = NULL;
        pthread_mutex_unlock(&lock);
        return -1;
    }
    /* Feature bits as uxplay.cpp sets them by default (no HLS video, no H.265, AirPlay 2 pairing). */
    static const int features[32] = {
        0, 1, 1, 0, 0, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0, 0, 1, 0, 1, 0,
    };
    for (int bit = 0; bit < 32; bit++) dnssd_set_airplay_features(dnssd, bit, features[bit]);
    dnssd_set_airplay_features(dnssd, 42, 0);
    /* AirPlay video (as uxplay.cpp does for -hls): bit 0 video, bit 4 HTTP Live Streaming */
    dnssd_set_airplay_features(dnssd, 0, config->video ? 1 : 0);
    dnssd_set_airplay_features(dnssd, 4, config->video ? 1 : 0);

    raop_callbacks_t raop_cbs;
    memset(&raop_cbs, 0, sizeof(raop_cbs));
    raop_cbs.conn_init = conn_init;
    raop_cbs.conn_destroy = conn_destroy;
    raop_cbs.conn_reset = conn_reset;
    raop_cbs.conn_feedback = conn_feedback;
    raop_cbs.audio_process = audio_process;
    raop_cbs.video_process = video_process;
    raop_cbs.audio_flush = audio_flush;
    raop_cbs.video_flush = video_flush;
    raop_cbs.video_pause = video_pause;
    raop_cbs.video_resume = video_resume;
    raop_cbs.audio_set_volume = audio_set_volume;
    raop_cbs.audio_get_format = audio_get_format;
    raop_cbs.report_client_request = report_client_request;
    raop_cbs.display_pin = display_pin;
    raop_cbs.register_client = register_client;
    raop_cbs.check_register = check_register;
    raop_cbs.video_reset = video_reset;
    raop_cbs.video_set_codec_and_size = video_set_codec_and_size;
    raop_cbs.audio_set_client_volume = audio_set_client_volume;
    raop_cbs.audio_set_metadata = audio_set_metadata;
    raop_cbs.audio_set_coverart = audio_set_coverart;
    raop_cbs.audio_stop_coverart_rendering = audio_stop_coverart_rendering;
    raop_cbs.audio_remote_control_id = audio_remote_control_id;
    raop_cbs.audio_set_progress = audio_set_progress;
    raop_cbs.export_dacp = export_dacp;
    raop_cbs.mirror_video_running = mirror_video_running;
    raop_cbs.on_video_play = on_video_play;
    raop_cbs.on_video_scrub = on_video_scrub;
    raop_cbs.on_video_rate = on_video_rate;
    raop_cbs.on_video_stop = on_video_stop;
    raop_cbs.on_video_acquire_playback_info = on_video_acquire_playback_info;
    raop_cbs.on_video_playlist_remove = on_video_playlist_remove;
    raop_cbs.get_custom_profile = get_custom_profile;
    /* passwd stays NULL: UxPlay checks it, and NULL means "no password" */

    raop = raop_init(&raop_cbs);
    if (!raop) {
        stop_locked();
        pthread_mutex_unlock(&lock);
        return -2;
    }
    raop_set_log_callback(raop, on_log, NULL);
    raop_set_log_level(raop, LOGGER_INFO);

    char device_id[18];
    ap_format_device_id(config->hw_addr, device_id);
    if (raop_init2(raop, 1 /* nohold: a new client takes over */, device_id, config->keyfile ? config->keyfile : "")) {
        free(raop);
        raop = NULL;
        stop_locked();
        pthread_mutex_unlock(&lock);
        return -3;
    }
    if (config->width) raop_set_plist(raop, "width", config->width);
    if (config->height) raop_set_plist(raop, "height", config->height);
    if (config->fps) {
        raop_set_plist(raop, "refreshRate", config->fps);
        raop_set_plist(raop, "maxFPS", config->fps);
    }
    if (config->video) {
        static const char none[] = "";
        raop_set_plist(raop, "hls", 1);
        raop_set_lang(raop, none, none, none); /* keeps the pointers: must outlive raop */
    }

    unsigned short tcp[3] = { 0, 0, 0 };
    unsigned short udp[3] = { 0, 0, 0 };
    raop_set_tcp_ports(raop, tcp);
    raop_set_udp_ports(raop, udp);
    unsigned short port = raop_get_port(raop);
    if (raop_start_httpd(raop, &port) < 0) {
        stop_locked();
        pthread_mutex_unlock(&lock);
        return -4;
    }
    raop_set_port(raop, port);
    raop_set_dnssd(raop, dnssd);

    if (dnssd_register_raop(dnssd, port) || dnssd_register_airplay(dnssd, port)) {
        stop_locked();
        pthread_mutex_unlock(&lock);
        return -5;
    }
    pthread_mutex_unlock(&lock);
    return 0;
}

void ap_stop(void) {
    pthread_mutex_lock(&lock);
    stop_locked();
    pthread_mutex_unlock(&lock);
}

int ap_port(void) {
    pthread_mutex_lock(&lock);
    int port = raop ? (int) raop_get_port(raop) : 0;
    pthread_mutex_unlock(&lock);
    return port;
}

bool ap_is_running(void) {
    pthread_mutex_lock(&lock);
    bool running = raop && raop_is_running(raop);
    pthread_mutex_unlock(&lock);
    return running;
}
