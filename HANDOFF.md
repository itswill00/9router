# Handoff — 9Router for Android (2026-11-27)

## Goal

Maintain the native Android port of 9Router: a Kotlin gateway that defaults to
loopback with opt-in private-network access, a Compose dashboard,
upstream-parity navigation/UX, reliable live data, and one sideloadable release
APK per version.

## Current state

- Branch: `master` — clean; synced with `origin/master`.
- Version: `0.3.0-android` (version code 38).
- Latest release (Latest): https://github.com/itswill00/9router/releases/tag/android-v0.3.0
  - asset: `9Router-0.3.0-release.apk` (11,317,475 bytes)
  - SHA-256: `5e4240ac18b83c7e63b8f812443becf376fae9de39d75830f3fd06879966dc6b`
- Recent commits:
  - `89501b81 chore(android): release 0.2.0`
  - `e8a30860 feat(ui): turn media pages into working request consoles`
  - `811a4d13 feat(media): add native image, audio and web gateway`
  - `5c2021b0 docs: record release 0.1.34`
  - `5a6efea4 chore(android): release 0.1.34`
  - `894a4c88 fix(i18n): translate interpolation templates`
  - `4aecdd79 feat(providers): allow multiple keys on custom endpoints`
  - `c136efb9 chore(android): release 0.1.33`
  - `e9d98dad fix(i18n): escape the placeholder regex for Android ICU`
- Working tree: no modified or untracked files after the docs commit.

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
- Custom endpoint keys (shipped in 0.1.34): `ProviderNode` keeps an ordered
  `apiKeys` list plus the legacy `apiKey`, the add/edit dialogs manage the list,
  ChatEngine synthesizes one connection per key (fill-first fallback and
  round-robin cursor), and NodeProbe probes until a key succeeds. The node API
  accepts `apiKeys` and reports `keyCount`. **Verified** by
  `ProviderNodeKeysTest` and the fast check; not yet exercised on a phone.
- Carbon & Ember design refresh (shipped in 0.3.0): new palette/typography
  tokens in `ui/Theme.kt`, bundled Manrope + JetBrains Mono (OFL assets), an
  ambient shell, an adaptive bottom dock/navigation rail in `ui/Shell.kt`, a
  Command Center hero in `ui/Screens.kt`, refreshed shared components, and a
  responsive 2/3/4-column provider grid. **Compiled and linted**, not visually
  exercised on a device.
- Media gateway v1 (shipped in 0.2.0): `core/MediaEngine.kt` implements image
  generation (OpenAI-compatible adapters + Gemini), OpenAI/ElevenLabs speech,
  OpenAI-compatible multipart transcription, and normalized search/fetch for
  Tavily, Exa, Brave, Serper, Jina and Firecrawl. `ProviderDef` gained
  `MediaConfig` blocks exported from the registry. `Gateway` exposes the five
  routes, writes binary responses, and filters `/v1/models/{kind}` to providers
  with a native adapter. The media pages now have a request console. **Verified**
  by `MediaEngineTest` (15 cases) and the fast check; not yet exercised against
  real provider keys on a phone.
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
for 0.3.0 also succeeded and the APK metadata was verified:

```text
package: com.ninerouter.android  versionCode=38  versionName=0.3.0-android
```

`ProviderNodeKeysTest` covers legacy-key migration, deduplication and cursor
reset; `MediaEngineTest` covers the new image, speech, search, fetch, multipart
and HTTP paths. The release APK still contains 34 `assets/i18n/*.json` files.

## Stuck on

Nothing is blocked. There is no active bug or failing test.

## Next steps

1. Read `AGENTS.md`; verify the tree is clean and `versionName` is
   `0.3.0-android` before touching code.
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
6. Known audit leftovers, deliberately not forced in this pass:
   GitHub Claude models still use the Chat Completions endpoint; registry
   `upstreamModelId` rewrites are not exported; keyless/noAuth and
   provider-specific executors (opencode, qoder, kiro, cursor, commandcode,
   Ollama, web-cookie) still need either a port or a structured 501; per-account
   quota cards are provider-wide; built-in providers can hold multiple keys but
    the bulk `name|key` paste flow is not ported; custom embedding nodes and
    provider-specific credential forms (Azure, Cloudflare, Bedrock, Ollama) are
    absent from the mobile UI; and the UI has not been exercised on a
   physical device since these changes.

## Pitfalls — do not repeat

- **Media is binary** → audio/image responses must go through `writeBytes`, not
  `writeJson`. The gateway's normal request path converts the body to UTF-8, so
  STT must read `request.body` as bytes; decoding multipart audio as a string
  corrupts it.
- **Do not advertise unimplemented media** → `/v1/models/{kind}` is filtered to
  providers with a native adapter. Keep `MediaEngine.supports` and the media
  page badges in sync when adding an adapter.
- **Gemini media auth is not Bearer** → API-key Gemini requests use
  `x-goog-api-key`; OAuth access tokens fall back to `Authorization: Bearer`.
- **Fonts and resource names** → `res/font` names must stay lowercase; the
  bundled Manrope/JetBrains Mono families are referenced through
  `NineRouterFonts` and must not fall back to `FontFamily.Monospace` in new
  code.
- **Adaptive shell insets** → the outer `AppShell` Row owns the navigation-bar
  and IME bottom inset. Do not add another navigation-bar padding to the
  bottom dock or content, or screens jump when the keyboard opens.
- **Android regex is ICU, not the JVM** → `Regex("""\{\d+}""")` compiled on the
  test JVM but threw `PatternSyntaxException` in `App.onCreate` on Android. A
  closing brace after a quantifier must be escaped too (`\{\d+\}`). JVM unit
  tests cannot prove a pattern is Android-safe; exercise regex-heavy startup
  code on a device or keep the pattern trivially escaped.
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
