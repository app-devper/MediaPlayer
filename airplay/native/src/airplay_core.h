/*
 * MediaPlayer AirPlay receiver: a small C API over UxPlay's raop/dnssd library.
 * Copyright (C) 2026 appdevper. GPL-3.0-or-later (links GPL-3.0 UxPlay code).
 */
#ifndef AIRPLAY_CORE_H
#define AIRPLAY_CORE_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Audio compression types as sent by the client ("ct"). */
#define AP_AUDIO_PCM 1
#define AP_AUDIO_ALAC 2
#define AP_AUDIO_AAC_LC 4
#define AP_AUDIO_AAC_ELD 8

/* Called on UxPlay's network threads. Any callback may be NULL. */
typedef struct ap_callbacks {
    void *ctx;
    void (*log)(void *ctx, int level, const char *msg);
    /* number of open client connections changed (0 = the last client left) */
    void (*connections)(void *ctx, int open);
    /* a client asks to connect; return false to refuse it */
    bool (*admit)(void *ctx, const char *device_id, const char *model, const char *name);
    /* PIN to show on screen while a new client pairs */
    void (*show_pin)(void *ctx, const char *pin);
    /* a client finished PIN pairing / is it already paired (pk = its public key, hex) */
    void (*register_client)(void *ctx, const char *device_id, const char *pk, const char *name);
    bool (*is_registered)(void *ctx, const char *pk);
    /* screen mirroring: codec + size, then H.264/H.265 Annex-B access units */
    void (*video_format)(void *ctx, bool h265, int width, int height);
    void (*video)(void *ctx, const uint8_t *data, int len, uint64_t pts_ns);
    /* mirroring ended (the client stopped or switched to something else) */
    void (*video_stop)(void *ctx);
    /* audio stream: compression type (AP_AUDIO_*) and samples per frame, then encoded frames.
     * ALAC is decoded here and reported as AP_AUDIO_PCM: 44100 Hz, 16 bit little-endian, stereo. */
    void (*audio_format)(void *ctx, int ct, int spf, bool using_screen);
    void (*audio)(void *ctx, const uint8_t *data, int len, uint64_t pts_ns);
    void (*audio_flush)(void *ctx);
    /* AirPlay volume in dB: -30 (min) .. 0 (max), -144 = mute */
    void (*audio_volume)(void *ctx, float db);
    /* connection lost or the client went away uncleanly: the app should restart the receiver */
    void (*reset)(void *ctx, int reason);

    /* AirPlay video (YouTube, Safari…): play [url] (http/https, or this receiver's local HLS
     * server for YouTube) from [start_seconds]; then seek / rate (0 pause, 1 play) / stop. */
    void (*video_play)(void *ctx, const char *url, float start_seconds);
    void (*video_scrub)(void *ctx, float seconds);
    void (*video_rate)(void *ctx, float rate);
    void (*video_playback_stop)(void *ctx);
    /* What the app's player is doing, for the sender's progress bar. Return false while nothing is
     * loaded; set *duration < 0 once the video finished. Called on a network thread: no blocking. */
    bool (*video_info)(void *ctx, double *duration, double *position, float *rate);
} ap_callbacks_t;

typedef struct ap_config {
    const char *name;           /* shown in the sender's AirPlay menu */
    uint8_t hw_addr[6];         /* stable device id (MAC-like) */
    const char *keyfile;        /* where the Ed25519 identity is kept, so paired clients stay paired */
    int width, height, fps;     /* display the client should target (0 = 1920x1080@30) */
    bool require_pin;           /* first connection from a client needs the on-screen PIN */
    bool video;                 /* accept AirPlay video (HLS / URLs) besides mirroring and audio */
    int log_level;              /* UxPlay logger level passed to the log callback (0 = default: info) */
} ap_config_t;

/* Starts advertising and accepting clients. Returns 0 on success. Only one receiver per process. */
int ap_start(const ap_config_t *config, const ap_callbacks_t *callbacks);

/* Stops and unregisters. Safe to call when not running. */
void ap_stop(void);

bool ap_is_running(void);

/* The RTSP/HTTP port senders connect to, or 0 when not running. */
int ap_port(void);

/* "AA:BB:CC:DD:EE:FF" for [hw_addr]; out must hold 18 bytes. */
void ap_format_device_id(const uint8_t hw_addr[6], char *out);

#ifdef __cplusplus
}
#endif

#endif
