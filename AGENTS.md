# 9Router for Android — agent guide

This file is the durable working agreement for this repository. Read
`HANDOFF.md` first for the current state, then this file before changing code.

## Project identity

- 9Router is an open-source project created by **decolua** and developed with
  the 9Router contributors. This repository is a **fork and native Android
  port** maintained by **itswill00**.
- Never describe itswill00 as the founder or upstream author. The Android port
  is the contribution here.
- The app is Kotlin + Jetpack Compose + OkHttp + Android Keystore. The gateway
  runs as a foreground service on loopback (`127.0.0.1:20128`), with no Node,
  Termux, shell, root, or bundled Linux userland.
- Keep the upstream provider registry and design tokens; the runtime is an
  independent Kotlin implementation.

## Non-negotiable rules

### Product and privacy

- Native Android only. Do not add a Node/Termux/root runtime or a bundled
  JavaScript engine.
- The gateway binds loopback by default. Network mode is opt-in and must keep
  every guard: wildcard listener only to reach private interfaces, public
  source addresses dropped at accept time, peers limited to loopback +
  RFC1918/link-local/CGNAT/tailnet, API key mandatory, management routes
  requiring the primary key whenever auth is on (loopback too), no wildcard
  CORS in any mode, per-peer auth throttle, and masked key listings.
- Never terminate TLS in the app and never trust `X-Forwarded-For`. A public
  domain must end at a tunnel or reverse proxy.
- Never log, print, or render provider tokens, API keys, cookies, or OAuth
  secrets. Mask existing keys. Newly created keys may be shown once for copying.
- Preserve working routing, translation, auth, and usage logic when polishing
  UI.
- Use the existing dark/orange theme and components. Do not introduce a second
  visual system.

### Builds

- The development machine is a 2-core / 8 GB box. Never run the `full` build.
- Iterate with `./scripts/android-build.sh check` (unit tests + fatal lint).
- Build one plain release variant with `./scripts/android-build.sh release`.
- R8/minify is deliberately disabled; do not enable it.
- The release is signed with the shared debug/sideloading identity so in-app
  updates install in place. Do not change the signing identity.
- Debug APKs are local only and must never be attached to a GitHub release.
- Build once per version. Publish with
  `./scripts/android-release.sh --skip-build --yes`; do not rebuild during
  publishing.

### Git and GitHub

- Use the `git-commit` skill for every commit.
- Use `git-workflow-and-versioning` (or the relevant Git/GitHub skill) for any
  push, tag, PR, or release.
- Do not push, open a PR, tag, or release unless the user explicitly asks.
- Conventional Commits only: `feat|fix|refactor|chore|test|docs|build|ci|perf|style`.
  Subject ≤72 chars, imperative, lowercase first letter, no trailing period.
- One logical change per commit. Stage explicit files or hunks; never
  `git add .` blindly.
- Never commit secrets, tokens, `.env`, keystores, or local build output.
- Do not force-push `main`/`master`.
- Verify build/tests/lint before claiming work is done.
- `gh` is authenticated manually by the user; never store tokens in the repo.

### Release procedure

1. Bump `versionCode` by 1 and `versionName` to the next `0.1.x-android` in
   `android/app/build.gradle.kts`.
2. Update `android/RELEASE_NOTES.md`:
   - new `# 9Router Android <version>` heading;
   - new `## What changed since <previous>` section;
   - rename the previous `## What changed since ...` to
     `## What changed in <previous>`;
   - update the download/install filename and Build information version/code.
3. Run `./scripts/android-build.sh release` once.
4. Verify the APK with `aapt2 dump badging` and record its SHA-256.
5. Commit the version + notes as `chore(android): release <version>`.
6. Push `master` only when the user has asked for the release.
7. Run `./scripts/android-release.sh --skip-build --yes`. It tags
   `android-v<version>`, pushes the tag, and creates the release.
8. Verify with `gh release view`, confirm the single APK asset, title, tag, and
   clean tree. The title must be `9Router Android <version>`; the notes body
   must not repeat its leading `#` heading.

## UX and design rules

- Before changing navigation, combo, usage, or other upstream-facing concepts,
  **check the original source under `src/` first** and match its concept.
- The top bar owns the page title and subtitle. Do not repeat the same title in
  the page content. Use content headings only for subsections.
- Avoid duplicate actions, notes, cards, or visual status indicators.
- Long model ids must wrap or provide a full-detail path. The shared model
  picker groups by provider, shows the provider prefix once, wraps long ids,
  and supports custom ids and multi-select. Combos and Basic Chat must use the
  same picker; do not create a second picker.
- Data pages must refresh while open. Read live state in the screen's own
  composition scope with `val revision by storeRevision()` or
  `val revision by liveRevision(...)`; do not read it only inside a helper that
  returns a plain value.
- Remote access lives on Endpoint & Key: one toggle, the private URLs the phone
  is reachable on, and a Public base URL with a reachability test. Copy-config
  and the status page use the public URL when set. Do not add a second exposure
  control in Settings; Settings only reflects that the key is forced on.
- Usage Overview has six stat cards (Total Requests, Input, Cached, Output,
  Estimated Cost, Success Rate), a vertical provider flow, recent requests,
  charts, and the per-model table. Keep the 2-column phone / 3-column wide grid.
- Provider Topology is a **vertical route flow**, not a radial canvas: 9Router
  at the top, proportional bars per provider, top four on phones and six on
  tablets, with the long tail in an "Other" row.
- The hamburger is a 56 dp target moved inward from the gesture edge, with a
  visible 40 dp disc, focus clearing, top-bar z-index, and clipped content.
  Do not shrink it or move it back to the edge.
- Settings uses accented section headers and a max-width 760 dp layout.
- About is a project lineage: decolua (founder) and contributors first, then
  itswill00 as the Android port author. Keep the bundled profile photos,
  contributors graph, and MIT copyright notice.
- Keep the About/README credit accurate. Forks and redistributions must keep
  the MIT notice and the upstream credit.

## Architecture map

- `android/app/src/main/java/com/ninerouter/android/server/`
  `Gateway.kt`, `ApiHttp.kt`, `HttpRequestParser.kt`, `ApiAccess.kt`,
  `AuthThrottle.kt`, `CompatibilityRoutes.kt`, `ModelsApi.kt`,
  `EmbeddingsApi.kt`, `SseEncoder.kt`, `SseWriter.kt`.
- `core/`
  `ChatEngine.kt` (routing, combo strategies/fusion, usage),
  `Upstream.kt`, `Translator.kt`, `ModelResolver.kt`, `Capabilities.kt`,
  `ComboState.kt`, `Fallback.kt`, `Rtk.kt`, `PromptInjectors.kt`,
  `OAuthFlow.kt`, `ProviderProbe.kt`, `NodeProbe.kt`.
- `data/`
  `Store.kt` (encrypted file state + `version: StateFlow<Int>`),
  `Models.kt`, `Catalog.kt`, `Pricing.kt`, `Crypto.kt`, `ProviderAuth.kt`,
  `Exposure.kt` (network mode, peer allow-list, public URL policy).
- `ui/`
  `Shell.kt` (drawer/top bar), `Screens.kt` (Endpoint/Chat/Update),
  `ProvidersScreen.kt`, `CombosScreen.kt`, `UsageScreen.kt`,
  `ProviderTopologyCard.kt`, `SettingsScreen.kt`, `ToolScreens.kt`,
  `AboutScreen.kt`, `LogsScreen.kt`, `Components.kt`, `Theme.kt`,
  `LiveState.kt`, `I18n.kt` (runtime translator and Text shim).
- `update/UpdateManager.kt`; `scripts/android-build.sh`,
  `scripts/android-release.sh`, `scripts/export-android-config.mjs`.
- Localization: `ui/I18n.kt` (runtime), `assets/i18n/*.json` (generated),
  `android/tools/i18n/` (string extraction, overrides, dictionary builder).
- Docs: `README.md` (root), `android/README.md`,
  `android/RELEASE_NOTES.md`, `android/API_COMPATIBILITY.md`.

## Current implementation notes

- Combo strategies are per combo: fallback, sticky round-robin, smart router
  (Smart Switch, Strict Lock, Per-Turn, custom threshold), and fusion
  (parallel panel + judge). Vision/audio capacity adapters are global.
- Basic Chat sends a `session_id` for smart-router stickiness. The gateway
  accepts session ids from headers/body.
- Fusion runs panel members in parallel, strips tools for panel calls, and
  sends the judge the synthesized prompt. It waits for the full panel; the
  upstream quorum-grace window is not ported.
- `Store.save()` and `Store.mutate()` are synchronized; `Store.version` mirrors
  the Compose revision for explicit live subscriptions.
- `liveRevision` / `liveNow` return `State<T>`; callers must read `.value`
  (normally with `by`). A helper that returns a plain value only recomposes
  itself and leaves the screen stale — this was the cause of Console Log and
  Usage needing an app restart.
- Current release: `0.1.32-android` (code 34). See `HANDOFF.md` for the exact
  tag, SHA, and latest-release URL.

## Known limitations

- No in-app TLS and no `X-Forwarded-For` trust. Network mode is meant for a
  private hop behind a tunnel or reverse proxy; raw public exposure is dropped.
- No TLS/MITM interception, certificate injection, or Antigravity IDE
  impersonation.
- Native Kiro/Cursor/commandcode binary envelopes are not implemented.
- No cloud sync; image/TTS/STT/video routes are listed in Media Providers but
  only `/v1/embeddings` has a native route. Proxy Pools is informational.
- Translator coverage focuses on OpenAI, Claude, Gemini, Responses; thinking
  and tool-id edge cases are still being ported.
- `/v1/responses/compact` returns a structured 501.
- Fusion waits for the full panel; no quorum-grace.
- No session rename/search, non-image attachments, or full-screen image viewer
  yet.
- The desktop-only dashboard sections (MITM, tunnel, proxy-pool editor, media
  management, SSO) are not in the mobile UI.

## User working preferences

- The user writes Indonesian. Reply in Indonesian, directly, answer first.
- When they say "cek dulu", inspect the original source before changing.
- When they say "gas"/"langsung rilis", proceed with the requested action,
  including a release. Otherwise do not push or release.
- Use the relevant installed skill whenever the task matches one, especially
  `git-commit` and `git-workflow-and-versioning`.
- Keep changes scoped, verify with the fast `check` path, and report the commit
  and release state honestly.
