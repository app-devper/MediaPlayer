# Vendored: Apple ALAC decoder

- Upstream: https://github.com/macosforge/alac (`codec/`, decoder files only)
- Commit: `c38887c5c5e64a4b31108733bd79ca9b2496d987`
- License: Apache-2.0 (see `LICENSE`)

Copied unchanged (the encoder files are only built into the native tests). Used by `../src/alac_decoder.cpp` to turn AirPlay ALAC audio (ct=2) into PCM,
since Android has no ALAC decoder.
