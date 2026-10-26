/*
 * ALAC must be lossless through our decoder with the fixed AirPlay cookie: encode PCM with Apple's
 * encoder at AirPlay's packet size (352 frames, 16 bit stereo 44.1 kHz), decode, compare.
 * Copyright (C) 2026 appdevper. GPL-3.0-or-later.
 */
#include <cmath>
#include <cstdio>
#include <cstring>

#include "ALACEncoder.h"
#include "alac_decoder.h"

int main() {
    const int frames = ALAC_FRAMES_PER_PACKET, channels = ALAC_CHANNELS;
    AudioFormatDescription pcm = {44100, kALACFormatLinearPCM, kALACFormatFlagIsSignedInteger | kALACFormatFlagIsPacked,
                                  4, 1, 4, 2, 16, 0};
    AudioFormatDescription alac = {44100, kALACFormatAppleLossless, 1 /* 16-bit source */, 0, (uint32_t) frames, 0, 2, 0, 0};

    ALACEncoder encoder;
    encoder.SetFrameSize(frames);
    if (encoder.InitializeEncoder(alac) != 0) { std::puts("FAIL: encoder init"); return 1; }
    alac_decoder_t *decoder = alac_decoder_create();
    if (!decoder) { std::puts("FAIL: decoder init"); return 1; }

    int failures = 0;
    for (int packet = 0; packet < 50; packet++) {
        int16_t in[frames * channels], out[frames * channels];
        for (int i = 0; i < frames; i++) {
            double t = (packet * frames + i) / 44100.0;
            in[i * 2] = (int16_t) (12000 * std::sin(2 * M_PI * 440 * t) + (packet % 7) * 37);
            in[i * 2 + 1] = (int16_t) (9000 * std::sin(2 * M_PI * 660 * t + packet));
        }
        uint8_t encoded[frames * channels * 2 + 64];
        int32_t size = frames * channels * 2;
        if (encoder.Encode(pcm, alac, (uint8_t *) in, encoded, &size) != 0) { std::puts("FAIL: encode"); return 1; }
        std::memset(out, 0, sizeof(out));
        int decoded = alac_decoder_decode(decoder, encoded, size, out, frames);
        if (decoded != frames || std::memcmp(in, out, sizeof(in)) != 0) {
            std::printf("FAIL: packet %d decoded %d frames, match=%d\n", packet, decoded, std::memcmp(in, out, sizeof(in)) == 0);
            failures++;
        }
    }
    // Garbage must be rejected or decoded without crashing.
    uint8_t junk[200];
    for (int i = 0; i < 200; i++) junk[i] = (uint8_t) (i * 73);
    int16_t out[frames * channels];
    alac_decoder_decode(decoder, junk, sizeof(junk), out, frames);
    alac_decoder_destroy(decoder);
    if (failures) return 1;
    std::puts("alac_roundtrip_test: 50 packets bit-exact");
    return 0;
}
