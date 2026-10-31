/*
 * ALAC → 16-bit PCM for AirPlay audio (ct=2), over Apple's decoder in ../alac.
 * Copyright (C) 2026 appdevper. GPL-3.0-or-later.
 */
#ifndef ALAC_DECODER_H
#define ALAC_DECODER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct alac_decoder alac_decoder_t;

/* For AirPlay's fixed stream format: 44100 Hz, 16 bit, stereo, 352 frames per packet. */
alac_decoder_t *alac_decoder_create(void);

/* Decodes one packet into interleaved little-endian int16 samples. Returns frames decoded, or -1. */
int alac_decoder_decode(alac_decoder_t *decoder, const uint8_t *data, int len, int16_t *out, int max_frames);

void alac_decoder_destroy(alac_decoder_t *decoder);

#define ALAC_CHANNELS 2
#define ALAC_FRAMES_PER_PACKET 352

#ifdef __cplusplus
}
#endif

#endif
