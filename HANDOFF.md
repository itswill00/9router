# Handoff — 9Router for Android (2026-11-26)

## Goal

Maintain the native Android port of 9Router: a Kotlin gateway that defaults to
loopback with opt-in private-network access, a Compose dashboard,
upstream-parity navigation/UX, reliable live data, and one sideloadable release
APK per version.

## Current state

- Branch: `master` — clean, synced with `origin/master`.
- Version: `0.1.28-android` (version code 30).
- Unreleased: remote access, public base URL and network-mode hardening are
  committed on `master`; the next release should be `0.1.29-android`.
- Latest release: https://github.com/itswill00/9router/releases/tag/android-v0.1.28
  - asset: `9Router-0.1.28-release.apk` (10,437,884 bytes)
  - SHA-256: `b862f298b92653055d755397f9f9c56309b186c0ad54b5d59e126e65e78b2794`
- Latest commits:
  - `92fcb6ae chore(android): release 0.1.28`
  - `df156955 refactor(android): replace the radial topology with a vertical flow`
  - `c7262f2c chore(android): release 0.1.27`
- Working tree: no modified or untracked files.

## Done so far

- Native gateway/API parity, OAuth, provider registry, streaming chat, chat
  sessions, image attachments, in-app updater, encrypted state. **Verified**
  by unit tests and prior releases.
- Full navigation drawer matching 9Router, with Basic Chat on the top bar,
  About before Settings, and Settings/About cross-links. **Verified** by the
  release builds.
- Combo strategies: fallback, sticky round-robin, smart policies, fusion, and
  vision/audio capacity adapters (`core/ChatEngine.kt`, `core/ComboState.kt`,
  `core/Capabilities.kt`, `ui/CombosScreen.kt`, `ui/Screens.kt`). **Verified**
  by `ComboRotationTest` and the fast check.
- Shared provider-grouped model picker with wrapping ids, custom ids and
  multi-select; Basic Chat uses the same picker. **Written and compiled.**
- Live refresh: `Store.version` StateFlow plus `storeRevision`, `liveRevision`
  and `liveNow` in `ui/LiveState.kt`; Usage/Console Log/Quota and state-backed
  pages now read the returned `State` in their own scope. **Verified** by the
  fast check; not manually exercised on a device in this session.
- Settings rebuilt with accented sections, max-width layout, account/combo
  stickiness, key management and confirmations. **Written and compiled.**
- Usage: six stat cards including Success Rate, adaptive 2/3-column grid,
  recent requests, charts, per-model table. **Written and compiled.**
- Provider Topology replaced by a vertical route flow: 9Router hub, provider
  branches, proportional volume bars, top four on phones / six on tablets,
  long tail in an Other row. **Written and compiled.**
- About page is a project lineage: decolua as upstream founder, contributors
  credited, itswill00 as the Android port author, bundled avatars, MIT license
  and project links. **Written and compiled.**
- README root and `android/README.md` document the lineage, issue routing and
  current limitations. **Written.**
- Remote access: opt-in network mode in `data/Exposure.kt`,
  `server/Gateway.kt`, `server/ApiAccess.kt`, `server/AuthThrottle.kt` and
  `ui/Screens.kt`. Accepts loopback plus RFC1918/link-local/CGNAT/tailnet,
  drops public sources, forces the key, key-gates management routes, removes
  wildcard CORS, throttles failed keys, and adds a public base URL with a
  health + authenticated models test. **Verified** by `ExposureTest`,
  `ApiAccessTest`, `ApiCorsTest` and the fast check; not manually exercised on
  a device in this session.

## Build/test

```bash
./scripts/android-build.sh check
```

Last result: `BUILD SUCCESSFUL` (unit tests + fatal lint). The release build
for 0.1.28 also succeeded and the APK metadata was verified:

```text
package: com.ninerouter.android  versionCode=30  versionName=0.1.28-android
```

## Stuck on

Nothing is blocked. There is no active bug or failing test.

## Next steps

1. Read `AGENTS.md`; verify `git status` is clean and `versionName` is
   `0.1.28-android` before touching code.
2. Ask the user which backlog item to take. Do not invent a release or push
   without an explicit request.
3. If a release is requested, follow the release procedure in `AGENTS.md`:
   bump version/notes, run `./scripts/android-build.sh release` once, verify the
   APK, commit the release, push `master`, then run
   `./scripts/android-release.sh --skip-build --yes`.
4. Likely next areas: native tunnel/relay integration for one-tap public URLs,
   media-provider backends, proxy-pool transport, non-image attachments, session
   rename/search, full-screen image viewer, fusion quorum-grace, adaptive
   tablet layouts, localization.

## Pitfalls — do not repeat

- **Live pages looked stale** → the revision was read only inside a helper that
  returned a plain value, so only the helper recomposed. Use
  `val revision by storeRevision()` / `val revision by liveRevision(...)` in the
  screen's own scope.
- **Release rebuilds** → the user explicitly wants one build, then
  `android-release.sh --skip-build --yes`. Do not rebuild during publishing.
- **Exposure shortcuts** → do not drop the peer allow-list, the forced key, the
  management-route gate or the CORS removal to make a tunnel "just work".
  Public source addresses are rejected on purpose; put a proxy in front.
- **Duplicate page chrome** → the top bar already shows title/subtitle. Do not
  repeat the same title in content; only subsection headings are allowed.
- **Founder confusion** → itswill00 is the Android port author, not the
  founder. decolua founded upstream 9Router; keep the lineage and MIT credit.
- **Hamburger hard to hit** → it is deliberately 56 dp, inset from the gesture
  edge, with a 40 dp disc, focus clearing, top-bar z-index and clipped content.
  Do not shrink or move it back to the edge.
- **Model picker truncation** → long ids must wrap and be grouped by provider.
  Combos and Basic Chat share one picker; do not add a second chat-specific
  picker.
- **Combo concept drift** → strategy belongs on the combo card, not the create
  dialog; keep smart policies, fusion and capacity adapters intact.
- **Release script dirty-tree guard** → commit the version/notes before
  publishing; otherwise `android-release.sh` refuses to run.
