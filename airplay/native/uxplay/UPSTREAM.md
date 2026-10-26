# Vendored: UxPlay `lib/`

- Upstream: https://github.com/FDH2/UxPlay
- Commit: `82ad05ce34ec862851fa6ab1240f50f58fb7fa5a` (2026-10-25)
- License: GPL-3.0 (see `LICENSE`; `llhttp/` is MIT, see `llhttp/LICENSE-MIT`)

Copied unchanged, except:
- `dns_sd/` (Apple Bonjour backend) and the upstream `CMakeLists.txt` files are not included;
  `../CMakeLists.txt` builds these sources with the built-in `mdnsd/` responder on every platform.

- `crypto.c` `pk_to_base64()`: uses `EVP_EncodeBlock` instead of a `BIO_f_base64` chain, which
  BoringSSL doesn't provide (same output: base64 without newlines).

Any local change to these files must be listed here.
