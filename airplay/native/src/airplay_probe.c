/*
 * Manual test tool: runs the receiver on this machine and prints what arrives.
 *   airplay_probe [name] [seconds]
 * Copyright (C) 2026 appdevper. GPL-3.0-or-later.
 */
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include "airplay_core.h"

static volatile sig_atomic_t stop = 0;
static long video_frames = 0, audio_frames = 0;

static void on_signal(int s) { (void) s; stop = 1; }
static void on_log(void *c, int level, const char *msg) { (void) c; if (level <= 6) printf("[log %d] %s\n", level, msg); }
static void on_connections(void *c, int open) { (void) c; printf("[conn] open=%d\n", open); }
static bool on_admit(void *c, const char *id, const char *model, const char *name) {
    (void) c; printf("[admit] %s (%s) id=%s\n", name, model, id); return true;
}
static void on_pin(void *c, const char *pin) { (void) c; printf("[pin] %s\n", pin); }
static void on_video_format(void *c, bool h265, int w, int h) { (void) c; printf("[video] %s %dx%d\n", h265 ? "h265" : "h264", w, h); }
static void on_video(void *c, const uint8_t *d, int len, uint64_t pts) {
    (void) c; (void) pts;
    if (video_frames++ % 60 == 0) printf("[video] frame %ld len=%d first=%02x%02x%02x%02x%02x\n", video_frames, len, d[0], d[1], d[2], d[3], d[4]);
}
static void on_video_stop(void *c) { (void) c; printf("[video] stop\n"); }
static void on_audio_format(void *c, int ct, int spf, bool screen) { (void) c; printf("[audio] ct=%d spf=%d screen=%d\n", ct, spf, screen); }
static void on_audio(void *c, const uint8_t *d, int len, uint64_t pts) {
    (void) c; (void) d; (void) pts;
    if (audio_frames++ % 200 == 0) printf("[audio] frame %ld len=%d\n", audio_frames, len);
}
static void on_volume(void *c, float db) { (void) c; printf("[audio] volume %.1f dB\n", db); }
static void on_reset(void *c, int reason) { (void) c; printf("[reset] reason=%d\n", reason); }

int main(int argc, char **argv) {
    ap_config_t config = {
        .name = argc > 1 ? argv[1] : "MediaPlayer Probe",
        .hw_addr = { 0x02, 0x4d, 0x50, 0x12, 0x34, 0x56 },
        .keyfile = "/tmp/airplay_probe.pem",
    };
    int seconds = argc > 2 ? atoi(argv[2]) : 0;
    ap_callbacks_t cb = {
        .log = on_log, .connections = on_connections, .admit = on_admit, .show_pin = on_pin,
        .video_format = on_video_format, .video = on_video, .video_stop = on_video_stop,
        .audio_format = on_audio_format, .audio = on_audio, .audio_volume = on_volume, .reset = on_reset,
    };
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    int err = ap_start(&config, &cb);
    printf("ap_start = %d, running = %d\n", err, ap_is_running());
    fflush(stdout);
    if (err) return 1;
    for (int t = 0; !stop && (seconds == 0 || t < seconds); t++) { sleep(1); fflush(stdout); }
    ap_stop();
    printf("stopped; video frames=%ld audio frames=%ld\n", video_frames, audio_frames);
    return 0;
}
