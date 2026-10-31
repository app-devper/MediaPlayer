---
name: dev-flow
description: End-to-end delivery flow for app-devper/MediaPlayer (KMP DLNA player + media server for Android, iOS, Desktop): from an idea or bug through shaping, design, test-first build, verify, review, a PR into develop, a release PR into main, tag, GitHub release and back-merge. Names the skill to use at each stage. Use when starting any change, when unsure what comes next, or when asked to ship, release or hotfix.
---

# dev-flow: idea → release for MediaPlayer

One flow, nine stages. Claude does most of the reading (verify, review);
the user decides what to build, signs off on risk and approves what lands. Each stage names the skill that does the work and the
exit check that must hold before moving on. Skip a stage only where it says
so. Branching and PR mechanics are owned by `git-flow` and `pr`; this skill
only sequences them.

Skills named below live in two places. `.agents/skills/` in this repo holds
the ones about this repo: `dev-flow`, `git-flow`, `pr`. The generic ones —
`security-review`, `ui-ux-pro-max` — come from `app-devper/agent-skills`,
linked into `~/.claude/skills` by its `install.sh`. If a generic skill is
missing, clone that repo and run the script; never recreate one inside this
repo, because a same-named copy would be shadowed.

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
| `:domain` | models, repository interfaces, sessions (`PlaybackSession`, `MediaServerSession`, the receivers), use cases with behaviour of their own | import Compose, ktor, platform APIs |
| `:data` | UPnP stack (`upnp/`), DLNA server (`server/`), platform I/O (`platform/`), repository impls, `dataModule` | leak `Upnp*`/`Didl*` types past the repository (map in `mapper/`) |
| `:composeApp` | ViewModels (`feature/*`), app shell (`ui/shell/`), Koin wiring (`di/`), native players | reach into `:data` (ViewModels use domain sessions, repository interfaces, and use cases only where a rule lives in one; no one-line pass-through use cases) |
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
branch, and keep it small: a PR over ~1,000 changed lines (not counting
vendored code or generated files) almost always gets findings and is hard to
revert. Split it (e.g. engine first, UI second) unless the parts can't land
apart.

**Risk tier.** Decide it now from what the change touches (the change sets
the tier, not who wrote it); it decides stage 6 and how the PR is approved.
When rows overlap, the highest tier wins (a LAN receiver behind an off-by-default
setting is still High), except `release/*`, which is Medium as stated.

| Tier | Touches | Review (stage 6) | Approval (stage 7) |
|---|---|---|---|
| Low | docs (README), copy, tests only, additive app changes behind a setting that is off by default | `/code-review` | the user's "go" to open the PR covers it |
| Medium | ordinary app changes (UI, player, domain, data); the process itself (`.agents/skills/*`, `.github/pull_request_template.md`), since it decides how everything else is reviewed; `release/*` (always Medium: everything in it already passed its own PR's review at that PR's tier; the release review checks the bump and that each PR in the changelog went through) | `/code-review` | the user approves the PR before it lands |
| High | anything listening on the LAN or parsing what arrives from it (`data/server`, `data/upnp`, `:airplay`, native C/C++), crypto/pairing, file access, `.github/workflows/*`, build/signing config, `hotfix/*` | `/code-review` + `/security-review`, both blocking | the user reviews the risky files themselves (point them to the exact paths) and approves |

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

- AirPlay native or MediaCodec changes (`:airplay`): run the on-device tests too, on an
  emulator or phone: `./gradlew :airplay:connectedDebugAndroidTest`. Real mirroring needs an
  iPhone/iPad/Mac on the same Wi‑Fi (My Server › Receive › AirPlay).
- UI changes: render the screens (light/dark, phone/desktop, fake data),
  then open the PNGs and look at them:
  `SHOT_DIR=<dir> ./gradlew :composeApp:jvmTest --tests '*DesignShots*'`
- Behaviour changes: `run` skill / `./gradlew :composeApp:run` (desktop).
  Discovery and server changes need a real LAN check (a second device or a
  DLNA app/TV on the same Wi‑Fi).
- A fresh clone needs `local.properties` with `sdk.dir=…` (gitignored).

- **Show, don't claim.** Paste the command and its result (test counts,
  `BUILD SUCCESSFUL`, the screenshot, the log lines) instead of "tests
  pass". Anything not run is said as such ("not checked on iOS device").
- UI changes are also checked against the design rules: the tokens and
  components in stage 2, contrast ≥ 4.5:1 for text, touch targets ≥ 48dp /
  44pt, labelled controls. For a wider UI pass use the shared
  `ui-ux-pro-max` skill:
  `python3 ~/.claude/skills/ui-ux-pro-max/scripts/search.py "<query>" --domain ux`
  (or `--stack jetpack-compose`). If the skill is missing, install
  `agent-skills` first; until then use the rules above and say so.

**Exit:** everything above is green with its output shown, and the change
was seen working, not just compiled.

## 6. Review (before the PR, blocking)

A reviewer with fresh context reads the diff against the whole repo and the
change's intent: does it solve the problem (for a bug: the root cause, not
the symptom), does it fit the system, does it break something else.

- Every tier: `/code-review`. Medium and High also get a review from fresh
  context: a new subagent (Agent tool, not a fork of this session) given
  the diff range and the change's intent. Use `/simplify` for cleanups if
  the diff grew.
- High tier: also `/security-review`. Before acting on a finding, have a
  separate subagent try to refute it (read the code path, decide real or
  false positive with a confidence); keep only the ones that hold up.
- Each finding is tagged **Important** (a real bug or risk), **Nit**, or
  **Pre-existing** (not introduced by this change: note it, don't block on
  it, open an issue or a follow-up).
- **Every finding gets an answer**: fixed (with the commit), or closed with a
  one-line reason (false positive, intentional, not worth it). An open
  Important finding blocks the PR.
- Fix the **class** of bug, not just the instance the reviewer pointed at
  (grep for the same pattern), and add a test that fails without the fix.
- **Converge:** after the first full review, re-review only the new diff and
  don't repeat answered findings. After **three** review-and-fix rounds,
  stop and rethink the approach with the user instead of patching again.
- A finding that keeps coming back (across PRs) is fixed at the source: a
  rule in this skill, a test helper, a lint/convention plugin, or the
  design system — not by remembering it next time.

**Exit:** reviews done for the tier, every finding answered, fixes verified
again with stage 5 (output shown).

## 7. PR into develop

```bash
git push -u origin feature/<slug>
gh pr create --base develop --title "feat(scope): …" --body-file <filled template>
gh pr comment <n> --body "Approved by the author at $(git rev-parse HEAD)"   # the user's go covers this head
```

Use `.github/pull_request_template.md`: what and why, risk tier, testing
(the commands that ran and what they returned, screenshots for UI, what was
*not* checked), review (each finding and how it was answered), and **how to
revert**. Claude drafts it; the user is the author of record and signs off.

- **Push and open the PR only after the user says so.** Claude's own review
  never counts as the approval; the user directing the work is the required
  reviewer (High tier: they look at the risky paths themselves).
- An approval covers the commits it saw, recorded on the PR as above (full
  SHA). Every later approval (Medium/High sign-off, or confirming commits
  made after it) is recorded the same way at the new head. Commits after the
  last recorded SHA (review fixes) are summarised to the user and confirmed
  before they're pushed and landed (`pr` skill, step 2). Don't rebase an
  approved branch; if it had to be, the whole PR is approved again.

PRs into `develop` land with the `pr` skill (squash, delete the branch, sync
`develop`). Release and hotfix PRs stop here and land in stage 8 step 5.
Never push to `develop` directly.

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
4. **PR:** `gh pr create --base main --title "release: vX.Y.Z" --body-file <filled template>`:
   the changelog (PR list since the last tag) under "What and why", tier
   Medium, and under "How to revert": "users go back to the previous tag's
   artifacts; the fix goes forward as a `hotfix/*` PR". Don't revert a release
   squash on `main`: it carries every PR since the last tag, and the
   back-merge would remove them from `develop` too.
   CI `Linux (JVM + Android)` must be green, and run the stage-5 iOS check
   on the release branch. Stages 6–7 apply: `/code-review` of the release
   diff, and the user approves before it lands.
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
8. **Publish** the same artifacts two ways:
   - **GitHub release** (every platform; the only channel for Desktop):
     `gh release create vX.Y.Z --title "vX.Y.Z" --notes "<changelog>" <artifacts>`
   - **Firebase App Distribution** (Android and iOS testers; project `devperpos`, group `testers`):
     ```bash
     firebase appdistribution:distribute composeApp/build/outputs/apk/release/composeApp-release.apk \
       --project devperpos --app 1:1056670356976:android:84aa77dcdc33c0c8a73935 \
       --groups testers --release-notes "vX.Y.Z: <one line>"
     firebase appdistribution:distribute <path-to>.ipa \
       --project devperpos --app 1:1056670356976:ios:3b64c3033bcfb930a73935 \
       --groups testers --release-notes "vX.Y.Z: <one line>"
     ```
     Testers are added once with `firebase appdistribution:testers:add <email> --group-alias testers --project devperpos`.
     The iOS build must be signed with an ad-hoc or development profile that lists the testers' devices.
   - Store uploads (Play Console / App Store Connect) stay by hand and come later.

**Exit:** tag `vX.Y.Z` on `main`, a GitHub release built from that commit, the Android and iOS builds
on App Distribution, and `develop` contains `main`.

## 9. Hotfix: a shipped build is broken

`git-flow` hotfix recipe: `hotfix/<slug>` from `main`, at **High** tier, in
this order:

1. stages 4–6: a test reproducing the bug first, the fix, verify,
   `/code-review` + `/security-review`;
2. release step 3: the **patch** version bump, on the hotfix branch;
3. stage 7 up to opening the PR into `main` (titled `fix(scope): …`), only
   after the user says so and has looked at the fix;
4. release steps 5–8: land, tag, build, publish.

Urgency shortens the wait, not the steps.

## When something breaks

A bug that reached `develop` or a release: fix it the usual way (test that
reproduces it first), then ask **which stage should have caught it** — shape,
design, a missing test, verify, review, or the tier was too low — and fix that
stage (this skill, a test, CI) in the same PR or a follow-up. Revert first if
the fix isn't quick: every PR says how.

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
