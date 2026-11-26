# Handoff — 9Router for Android (2026-11-26)

## Goal

Maintain the native Android port of 9Router: a Kotlin gateway that defaults to
loopback with opt-in private-network access, a Compose dashboard,
upstream-parity navigation/UX, reliable live data, and one sideloadable release
APK per version.

## Current state

- Branch: `master` — clean; synced with `origin/master`.
- Version: `0.1.32-android` (version code 34).
- Latest release: https://github.com/itswill00/9router/releases/tag/android-v0.1.32
  - asset: `9Router-0.1.32-release.apk` (11,026,670 bytes)
  - SHA-256: `92b9aed94c64bd6969be27334d1cc9a4c6fcda43ed75ff59614c14b019aa6390`
- Recent commits:
  - `d7129f7a chore(android): release 0.1.32`
  - `fd1c7664 docs: record the localization pass`
  - `2a8c7c2d feat(i18n): add runtime language switching`
  - `f90662c3 docs: record release 0.1.31`
  - `1218ff01 chore(android): release 0.1.31`
  - `172e1f85 docs: record the unreleased audit pass`
  - `0d5d3407 fix(gateway): harden auth, keys and stream failures`
  - `a3d9ae20 fix(core): preserve native tool calls and stream usage`
  - `931d26ad fix(android): harden oauth, updates and background lifecycle`
  - `0cbddaca fix(ui): keep state live and remove interaction traps`
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
- Full front/back audit pass (shipped in 0.1.31): management routes now require the
  primary key in loopback too, stored keys are masked, CORS is gone, SSE
  failures cannot write a second HTTP response, connections are capped, and
  state writes are atomic with a backup. Native Claude/Responses translation,
  tool calls, images, Gemini URLs, Anthropic auth, stream usage and cache
  accounting were fixed. OAuth requires state, the updater rejects debug or
  unsigned APKs and verifies SHA-256, and the UI no longer loses drafts,
  auto-scrolls while the user reads, or exposes stale test results. **Verified**
  by the fast check and new `TranslatorNativeTest` cases.
- Runtime localization (shipped in 0.1.32): `ui/I18n.kt` shadows Compose `Text`, loads
  one dictionary per locale from `assets/i18n/<tag>.json`, supports `{0}`
  templates, and switches immediately through Settings → Language. The picker
  follows the system language until you choose one, covers the 35 upstream
  locales, and flips layout direction for Arabic, Hebrew, Farsi and Urdu. A
  curated English/Indonesian dictionary and upstream literals win over machine
  translation; single-word labels are only translated when curated. Tooling in
  `android/tools/i18n/` extracts the strings and rebuilds the assets. **Verified**
  by `I18nTest` and the fast check; not yet exercised on a physical phone.

## Build/test

```bash
./scripts/android-build.sh check
```

Last result: `BUILD SUCCESSFUL` (unit tests + fatal lint). The release build
for 0.1.32 also succeeded and the APK metadata was verified:

```text
package: com.ninerouter.android  versionCode=34  versionName=0.1.32-android
```

The localization pass kept the fast check green (`I18nTest`, 3 cases) and the
release APK contains 34 `assets/i18n/*.json` files.

## Stuck on

Nothing is blocked. There is no active bug or failing test.

## Next steps

1. Read `AGENTS.md`; verify the tree is clean and `versionName` is
   `0.1.32-android` before touching code.
2. Ask the user which backlog item to take. Do not invent a release or push
   without an explicit request.
3. If a release is requested, follow the release procedure in `AGENTS.md`:
   bump version/notes, run `./scripts/android-build.sh release` once, verify the
   APK, commit the release, push `master`, then run
   `./scripts/android-release.sh --skip-build --yes`.
4. Likely next areas: native tunnel/relay integration for one-tap public URLs,
   media-provider backends, proxy-pool transport, non-image attachments, session
   rename/search, full-screen image viewer, fusion quorum-grace, adaptive
   tablet layouts.
5. Verify the language picker on a physical phone (system default, Indonesian,
   an RTL locale, and a CJK locale) before the next release.
5. Known audit leftovers, deliberately not forced in this pass:
   GitHub Claude models still use the Chat Completions endpoint; registry
   `upstreamModelId` rewrites are not exported; keyless/noAuth and
   provider-specific executors (opencode, qoder, kiro, cursor, commandcode,
   Ollama, web-cookie) still need either a port or a structured 501; per-account
   quota cards are provider-wide; and the UI has not been exercised on a
   physical device since these changes.

## Pitfalls — do not repeat

- **Live pages looked stale** → the revision was read only inside a helper that
  returned a plain value, so only the helper recomposed. Read it unconditionally
  in the screen's own scope (`val revision = storeRevision().value`, or use the
  `by` value in a key that always runs), never only inside an `if` branch. This
  is what made the 0.1.29 remote-access toggle stay off.
- **Remote access toggle** → if it stops updating again, check the revision
  read at the top of `EndpointScreen` first, then the blank-key path.
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
