/*
 * ALAC → 16-bit PCM for AirPlay audio (ct=2), over Apple's decoder in ../alac.
 * Copyright (C) 2026 appdevper. GPL-3.0-or-later.
 */
#include "alac_decoder.h"

#include <new>

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"

/* The 'alac' atom AirPlay senders imply (same as UxPlay's GStreamer caps): frameLength 352,
 * bitDepth 16, pb 40, mb 10, kb 14, 2 channels, maxRun 255, sampleRate 44100. */
static const uint8_t COOKIE[] = {
    0x00, 0x00, 0x00, 0x24, 'a', 'l', 'a', 'c', 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x01, 0x60, 0x00, 0x10, 0x28, 0x0a, 0x0e, 0x02, 0x00, 0xff,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xac, 0x44,
};

struct alac_decoder {
    ALACDecoder decoder;
};

extern "C" alac_decoder_t *alac_decoder_create(void) {
    alac_decoder_t *d = new (std::nothrow) alac_decoder_t();
    if (!d) return nullptr;
    if (d->decoder.Init((void *) COOKIE, sizeof(COOKIE)) != 0) {
        delete d;
        return nullptr;
    }
    return d;
}

extern "C" int alac_decoder_decode(alac_decoder_t *d, const uint8_t *data, int len, int16_t *out, int max_frames) {
    if (!d || !data || len <= 0 || max_frames < ALAC_FRAMES_PER_PACKET) return -1;
    BitBuffer bits;
    BitBufferInit(&bits, (uint8_t *) data, (uint32_t) len);
    uint32_t frames = 0;
    if (d->decoder.Decode(&bits, (uint8_t *) out, ALAC_FRAMES_PER_PACKET, ALAC_CHANNELS, &frames) != 0) return -1;
    return (int) frames;
}

extern "C" void alac_decoder_destroy(alac_decoder_t *d) {
    delete d;
}
