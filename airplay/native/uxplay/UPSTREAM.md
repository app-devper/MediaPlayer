# Vendored: UxPlay `lib/`

- Upstream: https://github.com/FDH2/UxPlay
- Commit: `82ad05ce34ec862851fa6ab1240f50f58fb7fa5a` (2026-10-25)
- License: GPL-3.0 (see `LICENSE`; `llhttp/` is MIT, see `llhttp/LICENSE-MIT`)

Copied unchanged, except:
- `dns_sd/` (Apple Bonjour backend) and the upstream `CMakeLists.txt` files are not included;
  `../CMakeLists.txt` builds these sources with the built-in `mdnsd/` responder on every platform.

- `crypto.c` `pk_to_base64()`: uses `EVP_EncodeBlock` instead of a `BIO_f_base64` chain, which
  BoringSSL doesn't provide (same output: base64 without newlines).

- AirPlay video (HLS) hardening, since this app turns HLS support on:
  - `raop.c` `conn_request()`: AirPlay video requests (`X-Apple-Session-ID`, no `CSeq`) are refused
    with 403 unless the same address has a RAOP connection that completed pair-verify
    (`conn_has_verified_raop_peer`); upstream takes `/play` from anyone, bypassing PIN pairing.
    Local HLS server requests are only answered from loopback (`conn_is_loopback`).
  - `pairing.c/.h`: `pairing_session_is_verified()` for the check above.
  - `http_handlers.h` `scrub`/`rate`: a query without `=` dereferenced `NULL + 1`.

Any local change to these files must be listed here.
