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
  - `raop.c` `conn_request()`: AirPlay video requests (`X-Apple-Session-ID`, no `CSeq`) other than
    pairing/info (`is_pairing_url`) are refused with 403 unless that connection completed
    pair-verify (iOS pairs on the AirPlay connection itself for YouTube) or the same address has a
    verified RAOP connection (`conn_has_verified_raop_peer`); upstream takes `/play` from anyone,
    bypassing PIN pairing.
    Local HLS server requests are only answered from loopback (`conn_is_loopback`).
  - `pairing.c/.h`: `pairing_session_is_verified()` for the check above.
  - `http_handlers.h` `scrub`/`rate`: a query without `=` dereferenced `NULL + 1`.
  - `http_handlers.h` `supported_hls_proc_names`: adds `YouTubeMusic` (same HLS format as YouTube;
    upstream only logged it as unsupported, playback already worked).
  - `http_handlers.h` `POST /action` `playlistInsert` (upstream: "FIXME: not yet implemented"): with
    no current item, the inserted item is played like a `POST /play` body (`play_item`, split out of
    `http_handler_play`). YouTube Music swaps items this way when switching between video and song;
    in tests with an iPhone it never answered the FCUP request that follows, so the switch still ends in /stop.
    At debug log level the action body is logged.

Any local change to these files must be listed here.
