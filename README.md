# MediaPlayer (Kotlin Multiplatform)

A single Compose Multiplatform app that merges
[MediaPlayer](https://github.com/app-devper/MediaPlayer) and
[MediaServer](https://github.com/app-devper/MediaServer), and runs on **Android, iOS and Desktop**
(macOS / Windows / Linux).

| Feature | Android | iOS | Desktop |
|---|---|---|---|
| Find DLNA/UPnP media servers & renderers (SSDP) | ✅ | ✅ ¹ | ✅ |
| Browse server folders (ContentDirectory) | ✅ | ✅ | ✅ |
| Play video / music / photos on this device | ExoPlayer | AVPlayer | VLC (vlcj) ² |
| Play on a TV / speaker (AVTransport + volume) | ✅ | ✅ | ✅ |
| **AirPlay** (Apple TV, AirPlay speakers/TVs) | — | ✅ native | — |
| Built-in DLNA media server (share this device) | MediaStore | Documents folder | chosen folders |
| Receive casts: DLNA renderer (other apps "Play to" this device) ³ | ✅ | ✅ | ✅ |

¹ Real devices need Apple's multicast entitlement (see below). The simulator works without it.
² Without VLC installed, the desktop app opens media in the system player.
³ Off by default (My Server › Receive), since anyone on the Wi‑Fi could then play media on the screen.
  Plays while the app is open; only http(s) media URLs are accepted.

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
  (`MediaDevice`, `MediaEntry`, `PlaybackState`…), the repository interfaces, the use cases
  (`BrowseFolderUseCase`, `StartMediaServerUseCase`…) and `PlaybackSession` (queue, play target,
  renderer polling and auto-advance).
* **`:data`**: implements the repositories. Contains the pure-Kotlin UPnP stack (SSDP,
  description, SOAP, DIDL-Lite), the ktor-CIO DLNA server, and the platform code
  (multicast sockets, MediaStore / folders / iOS Documents) behind small interfaces.
* **`:composeApp`**: MVVM. One ViewModel per screen (`ShellViewModel`, `BrowseViewModel`,
  `PlayerViewModel`, `ServerViewModel`) exposes a `StateFlow` UI state and calls only use cases.
  Koin (`di/AppModule.kt`) is the composition root.
* **`:core:designsystem`**: Compose-only building blocks with no app or domain code: design
  tokens, the icon set and reusable components.

```
composeApp/src/commonMain/kotlin/com/appdevper/mediaplayer/
  di/          Koin modules + initKoin()
  feature/     home · browse · player · server · shell  (Screen + ViewModel)
  ui/shell/    routes, nav host, sidebar / drawer, top bar + "Play on" picker, mini player
  ui/          domain → UI helpers (MediaKind/MediaDevice icons, formatClock)
  platform/    expect: native player, AirPlay button, permissions

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

* **iOS:** the AirPlay button is in the top bar and inside the video player. When you pick an Apple
  TV or AirPlay 2 TV, the video moves there (`allowsExternalPlayback`); music keeps playing in the
  background (`UIBackgroundModes: audio`).
* **Android / Desktop:** these OSes have no AirPlay API, and current Apple TVs only accept senders
  that complete Apple's pairing handshake, so AirPlay isn't supported there. Use DLNA renderers.
  (Many TVs support both.)

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
