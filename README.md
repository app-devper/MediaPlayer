# MediaPlayer (Kotlin Multiplatform)

A single Compose Multiplatform app that merges
[MediaPlayer](https://github.com/app-devper/MediaPlayer) and
[MediaServer](https://github.com/app-devper/MediaServer), and runs on **Android, iOS and Desktop**
(macOS / Windows / Linux).

| Feature | Android | iOS | Desktop |
|---|---|---|---|
| Find DLNA/UPnP media servers & renderers (SSDP) | ✅ | ✅ ¹ | ✅ |
| Browse server folders (ContentDirectory) | ✅ | ✅ | ✅ |
| Play video / music / photos on this device | ExoPlayer | AVPlayer | FFmpeg (bytedeco) |
| Play on a TV / speaker (AVTransport + volume) | ✅ | ✅ | ✅ |
| **AirPlay** (Apple TV, AirPlay speakers/TVs) | — | ✅ native | — |
| Subtitles: sidecar .srt / .vtt next to a video (Thai TIS-620 too) | ✅ | ✅ | ✅ |
| Subtitles inside the file (text tracks) | ExoPlayer: MKV/MP4 SRT, ASS, WebVTT, mov_text | AVKit: MP4 mov_text, HLS WebVTT | FFmpeg: SRT, ASS, mov_text, WebVTT |
| Built-in DLNA media server (share this device) | MediaStore | Documents folder | chosen folders |
| Receive casts: DLNA renderer (other apps "Play to" this device) ³ | ✅ | ✅ | ✅ |
| **Receive AirPlay**: screen mirroring, AirPlay video (YouTube…) and audio from iPhone/iPad/Mac ⁴ | ✅ | — | ✅ |

¹ Real devices need Apple's multicast entitlement (see below). The simulator works without it.
³ Off by default (My Server › Receive), since anyone on the Wi‑Fi could then play media on the screen.
  Plays while the app is open; only http(s) media URLs are accepted.
⁴ Off by default (My Server › Receive); a sender's first connection needs the code shown on screen
  (can be turned off). iOS can't be an AirPlay receiver. Works while the app is open.

## Architecture (Clean Architecture + MVVM)

```
composeApp  (presentation)  ──►  domain  ◄──  data
  ViewModels, screens,             entities,       UPnP/DLNA stack, media server,
  Koin wiring, native players      repository      platform I/O, repository
        │                          contracts,      implementations
        ▼                          use cases,
core:designsystem                  PlaybackSession
  theme, icons, components
```

* **`:domain`**: pure Kotlin with no framework or platform code. Holds the models
  (`MediaDevice`, `MediaEntry`, `PlaybackState`…), the repository interfaces, the app-wide sessions
  (`PlaybackSession`, `MediaServerSession`, the DLNA and AirPlay receivers, each following its
  settings), and the few use cases with rules of their own (`BrowseFolderUseCase`…). `PlaybackSession`
  holds the queue, play target and auto-advance. The session sends commands to a transport: `LocalPlayer`, which holds the playback
  rules shared by every platform and drives a platform `PlaybackEngine`, or `RendererTransport` for
  a DLNA renderer. Its `nowPlaying` says what plays and where play/pause, next and previous go
  (an AirPlay sender's queue, through `SenderControl`); receivers reach it through a `ReceiverLink`.
  The terms are defined in `CONTEXT.md`.
* **`:data`**: implements the repositories. Contains the pure-Kotlin UPnP stack (SSDP,
  description, SOAP, DIDL-Lite), the ktor-CIO DLNA server and renderer (both run by one
  `LocalDevice` from a spec of their services), and the platform code (multicast sockets via
  `Network`, MediaStore / folders / iOS Documents) behind small interfaces. Discovery is tested on an
  in-memory LAN (`InMemoryLan`).
* **`:composeApp`**: MVVM. One ViewModel per screen (`ShellViewModel`, `BrowseViewModel`,
  `PlayerViewModel`, `ServerViewModel`) exposes a `StateFlow` UI state (the player's is one
  `PlayerUi` model the screen only lays out) and calls domain sessions,
  repository interfaces and use cases.
  Koin (`di/AppModule.kt`) is the composition root.
* **`:airplay`**: the AirPlay receiver. UxPlay's protocol library (C, vendored in `native/uxplay`)
  with BoringSSL and libplist, built by CMake for Android (NDK) and for the desktop build machine,
  behind a small JNI API (`AirPlayReceiver`). `AirPlayPlayer` plays what it receives: MediaCodec on
  Android, FFmpeg (bytedeco) on the desktop; ALAC is decoded natively with Apple's decoder.
* **`:core:testing`**: test fakes for the domain's interfaces (engine, renderer, receivers,
  settings, subtitle files), shared by the tests of every module. Never a main dependency.
* **`:core:designsystem`**: Compose-only building blocks with no app or domain code: design
  tokens, the icon set and reusable components.

```
domain/src/commonMain/kotlin/com/appdevper/mediaplayer/domain/
  model/       Media · Playback · Server · Cast · AirPlay (one file per area)
  repository/  repository interfaces (implemented in :data)
  usecase/     use cases with a rule of their own (no pass-throughs), one per file
  session/     app-wide sessions: PlaybackSession, MediaServerSession, CastReceiverSession, AirPlayReceiverSession;
               LocalPlayer + PlaybackEngine, RendererTransport

data/src/commonMain/kotlin/com/appdevper/mediaplayer/data/
  upnp/        SSDP, device descriptions, SOAP, DIDL-Lite (control point); host/ = our own devices
  server/      the DLNA media server      renderer/  the DLNA renderer (receive casts)
  repository/  repository implementations   mapper/  Upnp*/Didl* → domain
  platform/    platform I/O behind small interfaces   di/  dataModule

composeApp/src/<platform>Main/kotlin/com/appdevper/mediaplayer/
  di/          Koin modules + initKoin()
  feature/     home · browse · player · mirror · server · shell  (Screen + ViewModel)
  ui/          domain → UI helpers;  shell/ = routes, nav host, sidebar, top bar, mini player;
               player/ = LocalPlayerView (the local player on screen, every platform) + PlayerControlBar
  platform/    expect/actual UI: the video surface, AirPlay button and mirror view,
               full screen, permissions
  playback/    the PlaybackEngine per platform: ExoPlaybackEngine (Android), AvPlaybackEngine (iOS),
               FfmpegPlaybackEngine + FfmpegPlayer (desktop); BackgroundPlayer + PlaybackService
               (Android media session), IosBackgroundPlayer (iOS lock screen)
  service/     Android services: ServerService (keeps the DLNA server alive)

core/designsystem/src/commonMain/kotlin/com/appdevper/mediaplayer/designsystem/
  theme/       design tokens (colors, type, radii), light & dark
  icons/       outline icon set + logo
  components/  pill buttons, list rows, composer field, thin slider…

build-logic/   Gradle convention plugins shared by every module:
               mediaplayer.kmp.library · mediaplayer.kmp.application · mediaplayer.kmp.compose
```

Source files hold one top-level class (or one closely related group of composables) each.

## Design

The UI follows the ChatGPT style:
* near-monochrome palette with a light-grey sidebar (`#F9F9F9` / `#171717`)
* black/white primary pills, hairline borders, and one green status color
* a centered content column (max 768dp) with a rounded "composer" search field at the bottom
* a "Play on ▾" picker in the top bar, styled like ChatGPT's model picker
* a sidebar that is a modal drawer on phones and collapses on wide screens
* thin outline icons (Lucide geometry, 1.75px stroke) in `core/designsystem/.../icons/AppIcons.kt`

**App icon:** master files are `art/app-icon.svg` (full-bleed) and `art/app-icon-rounded.svg`.
* Android uses an adaptive vector (`res/drawable/ic_launcher_foreground.xml`, with a themed
  monochrome layer), plus PNG mipmaps for API < 26.
* iOS uses `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset`.
* Desktop uses `composeApp/icons/icon.{icns,ico,png}`.

**Screenshots:** `SHOT_DIR=/some/dir ./gradlew :composeApp:jvmTest --tests '*DesignShots*'`
renders Home, Browse, Player and Server with fake data in light and dark, at phone and desktop
sizes. Without `SHOT_DIR` the test does nothing.

## Build & run

```bash
./gradlew :composeApp:run                    # desktop
./gradlew :composeApp:installDebug           # Android device/emulator
./gradlew :composeApp:packageDistributionForCurrentOS   # .dmg / .msi / .deb
./gradlew jvmTest                            # all tests: domain, data (incl. end-to-end server), presentation
open iosApp/iosApp.xcodeproj                 # iOS: pick a simulator/device and Run
```

## AirPlay

### Receiving (Android, Desktop)

Turn on **My Server › Receive › AirPlay from iPhone, iPad and Mac**. The app then shows up in
Screen Mirroring (Control Center) and in AirPlay audio menus on Apple devices on the same Wi‑Fi:

* screen mirroring (H.264) opens the Mirror screen; the sender's audio plays with it
* **AirPlay video** (the AirPlay button in YouTube, YouTube Music, Safari and other apps): the video itself plays in
  the app's player (HLS on Android via ExoPlayer, on the desktop via FFmpeg), controlled from the sender,
  which also gets the progress
* music/podcasts (ALAC or AAC) play without a screen
* the first connection from each sender asks for the code shown on screen (switchable); paired senders
  are remembered
* the sender's volume control works; our own player pauses while a sender plays

### Sending

* **iOS:** the AirPlay button is in the top bar and inside the video player. When you pick an Apple
  TV or AirPlay 2 TV, the video moves there (`allowsExternalPlayback`); music keeps playing in the
  background (`UIBackgroundModes: audio`).
* **Android / Desktop:** sending to an Apple TV isn't supported (these OSes have no AirPlay sender
  API). Use DLNA renderers; many TVs support both.

## iOS notes

* **Multicast entitlement.** iOS 14+ blocks SSDP multicast on real devices unless the app has
  `com.apple.developer.networking.multicast`.
  [Request it from Apple](https://developer.apple.com/contact/request/networking-multicast), then set
  *Signing & Capabilities → Code Signing Entitlements* to `iosApp/iosApp.entitlements`.
* **Shared media** comes from the app's Documents folder: *Files → On My iPhone → Media Player*,
  or drag files in with Finder.
* iOS suspends networking in the background, so the server only runs while the app is open (the
  screen is kept awake while sharing).

## Android notes

* Media permissions (`READ_MEDIA_*`) are requested when you turn the server on.
* While the server is running, a foreground service (type `connectedDevice`) shows a notification
  with a Stop button, and holds the Wi-Fi + multicast locks.

## License

MediaPlayer is free software under the **GNU General Public License v3.0** (see `LICENSE`), because
it includes UxPlay's AirPlay library. If you distribute the app, its complete source must be
available under the same license.

AirPlay receiving relies on a reverse-engineered implementation of Apple's protocol (including
FairPlay authentication, from UxPlay/playfair). It is not licensed or endorsed by Apple; app stores may
reject or remove apps with it.

Third-party code in this repository or fetched by the build:

| Component | Where | License |
|---|---|---|
| UxPlay `lib/` (incl. playfair) | `airplay/native/uxplay` | GPL-3.0 |
| llhttp | `airplay/native/uxplay/llhttp` | MIT |
| Apple ALAC decoder | `airplay/native/alac` | Apache-2.0 |
| BoringSSL | fetched by CMake (pinned) | OpenSSL / ISC |
| libplist | fetched by CMake (pinned) | LGPL-2.1+ |
| FFmpeg + JavaCPP (bytedeco) | desktop runtime dependency | LGPL-2.1+ / Apache-2.0 |

