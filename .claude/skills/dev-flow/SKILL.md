---
name: dev-flow
description: End-to-end delivery flow for app-devper/MediaPlayer (KMP DLNA player + media server for Android, iOS, Desktop): from an idea or bug through shaping, design, test-first build, verify, review, a PR into develop, a release PR into main, tag, GitHub release and back-merge. Names the skill to use at each stage. Use when starting any change, when unsure what comes next, or when asked to ship, release or hotfix.
---

# dev-flow: idea → release for MediaPlayer

One flow, nine stages. Each stage names the skill that does the work and the
exit check that must hold before moving on. Skip a stage only where it says
so. Branching and PR mechanics are owned by `git-flow` and `pr`; this skill
only sequences them.

## 0. Pick the track

| Track | Starts at | Branch from → PR into |
|---|---|---|
| Feature / change | 1 | `develop` → `develop` (`feature/<slug>`) |
| Bug fix (not urgent) | 1 (short) | `develop` → `develop` (`feature/fix-<slug>`) |
| Refactor | 1 | `develop` → `develop` (`feature/refactor-<slug>`) |
| Release | 8 | `develop` → `main` (`release/X.Y.Z`) |
| Hotfix (shipped build broken) | 9 | `main` → `main` (`hotfix/<slug>`) |

## 1. Shape: agree on what and why

- Ask until the change is unambiguous: which platform(s) are affected
  (Android / iOS / Desktop), local playback or a DLNA renderer, player side
  or the built-in server side.
- DLNA interop bugs: get the device (TV/NAS make + model). If you can, get
  its description XML or the SOAP response too. These become test fixtures
  in stage 4.
- Small, well-understood fix: a couple of questions, then move on.

**Exit:** one or two sentences stating the change and how we will know it works.

## 2. Design: decide the shape of the code

Respect the layers (see README › Architecture):

| Layer | Owns | Must not |
|---|---|---|
| `:domain` | models, repository interfaces, use cases, `PlaybackSession` | import Compose, ktor, platform APIs |
| `:data` | UPnP stack (`upnp/`), DLNA server (`server/`), platform I/O (`platform/`), repository impls, `dataModule` | leak `Upnp*`/`Didl*` types past the repository (map in `mapper/`) |
| `:composeApp` | ViewModels (`feature/*`), app shell (`ui/shell/`), Koin wiring (`di/`), native players | call repositories directly (ViewModels use use cases only) |
| `:core:designsystem` | theme tokens (`theme/`), icons (`icons/`), components (`components/`) | depend on `:domain`, `:data` or app code (domain → UI helpers live in `composeApp/.../ui/`) |

- New platform behaviour: an interface in `data/platform` bound in each
  `platformDataModule`. Use an `expect` only for stateless functions.
  UI-only platform pieces (player, AirPlay, permissions) go in
  `composeApp/.../platform/PlatformUi.kt`.
- UI: use the tokens in `designsystem/theme` (`AppTheme.colors`, `Radius`),
  icons from `designsystem/icons/AppIcons`, and building blocks from
  `designsystem/components`. Don't use raw Material colors, ripples or
  Material icons. The ChatGPT-style monochrome look is the design language.
- New screen: `feature/<name>/<Name>Screen.kt` + `<Name>ViewModel.kt`, a
  route in `ui/shell/Routes.kt` wired in `ui/shell/AppNavHost.kt`, and the
  ViewModel registered in `presentationModule`.
- New module: apply `mediaplayer.kmp.library` (+ `mediaplayer.kmp.compose`
  for UI) from `build-logic`; don't repeat targets or Android SDK config.
  Add its `jvmTest` to the CI gate if it has tests.
- One top-level class per file; group only small, tightly related
  composables.

**Exit:** the files and modules to touch are known, with no layer rule broken.

## 3. Branch

`git-flow` → `feature/<slug>` from an up-to-date `develop`. One concern per
branch.

## 4. Build

Test first, in the layer that owns the behaviour:

- domain logic → `domain/src/commonTest` (fake repositories, `runTest`;
  see `PlaybackSessionTest`)
- protocol / parsing → `data/src/commonTest/UpnpTest.kt` (paste real
  device XML as fixtures); server behaviour → `data/src/jvmTest/MediaServerE2ETest.kt`
- ViewModels → `composeApp/src/jvmTest/PresentationTest.kt` with
  `Fakes.kt`; a new ViewModel also goes into `koinGraphResolvesEveryViewModel`

Commit in small conventional commits: `feat(scope): …`, `fix(scope): …`,
`refactor(scope): …`, `test(scope): …`, `chore(scope): …`. Scopes:
`domain`, `data`, `upnp`, `server`, `ui`, `player`, `android`, `ios`,
`desktop`, `build`.

## 5. Verify locally (CI gate + what CI can't cover)

```bash
# CI gate "Linux (JVM + Android)" (.github/workflows/check.yml)
./gradlew --parallel :domain:jvmTest :airplay:jvmTest :data:jvmTest :composeApp:jvmTest :composeApp:assembleDebug

# iOS: CI runs on Linux and never compiles it; check it before every PR that touches shared code
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
(cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO -derivedDataPath build/dd build)
```

- UI changes: render the screens (light/dark, phone/desktop, fake data),
  then open the PNGs and look at them:
  `SHOT_DIR=<dir> ./gradlew :composeApp:jvmTest --tests '*DesignShots*'`
- Behaviour changes: `run` skill / `./gradlew :composeApp:run` (desktop).
  Discovery and server changes need a real LAN check (a second device or a
  DLNA app/TV on the same Wi‑Fi).
- A fresh clone needs `local.properties` with `sdk.dir=…` (gitignored).

**Exit:** everything above is green, and the change was seen working, not
just compiled.

## 6. Review

`/code-review` on the diff, `/simplify` for cleanups, and `/security-review`
when touching the HTTP server, SOAP parsing or anything exposed to the LAN.
Fix findings before opening the PR.

## 7. PR into develop

```bash
git push -u origin feature/<slug>
gh pr create --base develop --title "feat(scope): …" --body "…"
```

Body: what changed, why, how it was verified (stage 5, including the iOS
check). Land it with the `pr` skill (squash, delete the branch, sync
`develop`). Never push to `develop` directly.

## 8. Release: develop → main

1. **Pick the version** from what landed since the last tag
   (`git log $(git describe --tags --abbrev=0 --match 'v*' origin/main 2>/dev/null || git rev-list --max-parents=0 HEAD)..origin/develop --oneline`):
   breaking → major, any `feat` → minor, only `fix` → patch.
2. **Cut the branch:** `git-flow` release recipe → `release/X.Y.Z` from `develop`.
3. **Version:** bump in all three places, in the same commit:
   - `gradle/libs.versions.toml`: `app-version` (Android/Desktop) and
     `app-versionCode` (+1, Android)
   - `iosApp/iosApp/Info.plist`: `CFBundleShortVersionString` and `CFBundleVersion`
   - `ServerScreen.kt`: the "Version" row
4. **PR:** `gh pr create --base main --title "release: vX.Y.Z" --body "<changelog: PR list since last tag>"`.
   CI `Linux (JVM + Android)` must be green, and run the stage-5 iOS check
   on the release branch.
5. **Land:** `pr` skill (squash into `main`; it also back-merges into `develop`).
6. **Tag:** nothing tags automatically.
   ```bash
   git checkout main && git pull --ff-only
   git tag -a vX.Y.Z -m "vX.Y.Z" HEAD   # HEAD = the "release: vX.Y.Z (#n)" squash commit
   git push origin vX.Y.Z
   ```
7. **Build artifacts** from that clean `main` checkout:
   ```bash
   ./gradlew :composeApp:packageDistributionForCurrentOS   # .dmg on macOS (.msi/.deb on those OSes)
   ./gradlew :composeApp:assembleRelease                   # sign with the release key (never committed)
   # iOS: Xcode › Product › Archive (needs the multicast entitlement for device builds)
   ```
8. **Publish:** `gh release create vX.Y.Z --title "vX.Y.Z" --notes "<changelog>" <artifacts>`;
   store uploads (Play Console / App Store Connect) are done by hand.

**Exit:** tag `vX.Y.Z` on `main`, a GitHub release built from that commit, and `develop` contains `main`.

## 9. Hotfix: a shipped build is broken

`git-flow` hotfix recipe: `hotfix/<slug>` from `main` → stages 4–6 (a test
reproducing the bug first) → PR into `main` titled `fix(scope): …` → release
steps 3 and 5–8 with a **patch** bump.

## Guard rails

- No direct pushes to `main` or `develop`, except the back-merge
  `main` → `develop` (admin only, see `git-flow`). Every other change goes
  through a PR with `Linux (JVM + Android)` green.
- Only `release/*` and `hotfix/*` PRs target `main`, and every merge into
  `main` is a release that gets tagged.
- Never tag a commit that is not on `main`; never move or delete a pushed tag.
- Never commit signing material (keystores, `*.jks`, `*.p12`,
  provisioning profiles), `local.properties` or API secrets. This repo's
  history had to be rewritten once to remove a leaked keystore.
