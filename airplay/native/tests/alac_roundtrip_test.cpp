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

/* MSB-first bit writer for hand-made ALAC packets. */
struct Bits {
    uint8_t data[4096] = {0};
    size_t pos = 0;
    void put(uint32_t value, int count) {
        for (int i = count - 1; i >= 0; i--, pos++) {
            if ((value >> i) & 1) data[pos / 8] |= (uint8_t) (0x80 >> (pos % 8));
        }
    }
};

/* A frame from the network claiming more samples than a packet holds must be refused, not
 * written past the decoder's buffers or ours (the canary right after the output). */
static int oversizedPartialFrameIsRejected(alac_decoder_t *decoder, uint32_t tag) {
    Bits b;
    b.put(tag, 3);          // ID_SCE (0) or ID_CPE (1)
    b.put(0, 4);            // element instance
    b.put(0, 12);           // unused header
    b.put(0x9, 4);          // partial frame = 1, bytes shifted = 0, escape (uncompressed) = 1
    b.put(0x00010000, 32);  // 65536 samples
    for (int i = 0; i < 1500; i++) b.put(0x7fff, 16);
    struct { int16_t out[ALAC_FRAMES_PER_PACKET * ALAC_CHANNELS]; int16_t canary[4096]; } mem;
    std::memset(mem.canary, 0x5a, sizeof(mem.canary));
    int decoded = alac_decoder_decode(decoder, b.data, (int) sizeof(b.data), mem.out, ALAC_FRAMES_PER_PACKET);
    for (int16_t c : mem.canary) {
        if (c != 0x5a5a) { std::printf("FAIL: tag %u wrote past the output buffer\n", tag); return 1; }
    }
    if (decoded != -1) { std::printf("FAIL: tag %u oversized frame decoded as %d frames\n", tag, decoded); return 1; }
    return 0;
}

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
    failures += oversizedPartialFrameIsRejected(decoder, 0);
    failures += oversizedPartialFrameIsRejected(decoder, 1);
    alac_decoder_destroy(decoder);
    if (failures) return 1;
    std::puts("alac_roundtrip_test: 50 packets bit-exact, oversized frames rejected");
    return 0;
}
