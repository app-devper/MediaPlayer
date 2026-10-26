# Vendored: Apple ALAC decoder

- Upstream: https://github.com/macosforge/alac (`codec/`, decoder files only)
- Commit: `c38887c5c5e64a4b31108733bd79ca9b2496d987`
- License: Apache-2.0 (see `LICENSE`)

Copied unchanged (the encoder files are only built into the native tests), except:
- `ALACDecoder.cpp` `Decode()`: the sample count, requested or read from a partial-frame header in
  the packet, must be `<= frameLength`. Upstream doesn't check it, so a crafted frame from the
  network overflowed the mix/predictor buffers and the output buffer. Used by `../src/alac_decoder.cpp` to turn AirPlay ALAC audio (ct=2) into PCM,
since Android has no ALAC decoder.
