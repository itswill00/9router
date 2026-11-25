# 9Router Android 0.1.11

Native Android build of the 9Router routing gateway. It is written in Kotlin and
runs entirely on the device. **No Termux, no Node.js, no shell, no root, and no
bundled Linux userland are required.**

## What changed since 0.1.10

- **Edge-to-edge is now first-class.** `MainActivity` uses
  `enableEdgeToEdge` with explicit dark system-bar styling (the app is always
  dark), the navigation-bar contrast scrim is disabled, and the content area
  consumes IME insets so text fields stay visible above the keyboard.
  `windowSoftInputMode="adjustResize"` was already set and is retained.
- **Predictive Back.** System back is handled with
  `androidx.navigationevent:navigationevent-compose` 1.0.0 instead of the legacy
  `BackHandler`: the provider detail and the tab shell each register a
  predictive-back handler, and the system Back callback is explicitly enabled.
  This required `compileSdk = 36` and AGP 8.9.1.
- **Compose performance pass.** Every lazy list now has stable keys and
  `contentType`s (providers, models, connections, API keys, combos, usage rows),
  chat messages carry a stable id, and the usage list uses `itemsIndexed` to
  avoid index lookups. The chat transcript auto-scrolls and keeps "sending"
  disabled until the stream finishes.
- **R8 is enabled for release builds.** `isMinifyEnabled` + `isShrinkResources`
  with `proguard-android-optimize.txt`, full mode on, and the standard
  kotlinx.serialization keep rules. `android analyze` reports R8 full mode and
  an obfuscation ratio of 0.97; the release DEX is about 2.5 MB.
- **Unit tests.** Five JVM tests cover custom-endpoint URL building (chat,
  Responses, Claude, `/models`, trailing slashes and case-insensitive types).
- **Smaller fixes.** The gateway status page shows the app version and the
  correct model count including custom nodes; the unused `WAKE_LOCK` permission
  was removed.
- **Build environment.** Gradle is capped at `-Xmx3g` with no idle daemon,
  `org.gradle.parallel=false` and two workers for this 2-core / 8 GB machine.

## What changed in 0.1.10

- **Custom endpoint pages are now real detail pages.** Tapping an OpenAI- or
  Anthropic-compatible node opens a page with its connection info, base URL and
  resolved chat/model endpoints, plus an editable prefix, name, API type and
  base URL. You can add or remove model ids, **Fetch from endpoint** to import
  the upstream `/models` catalog, and **Test** any model with a short inference
  request that reports the reply and latency.
- **Localhost troubleshooting built into the Endpoint tab.** An **Open dashboard**
  button launches `http://127.0.0.1:20128` in the browser, **Test connection**
  calls `/api/health` and shows the result, and the page explains that a
  computer needs `adb reverse tcp:20128 tcp:20128`. **Restart gateway** and
  **Battery settings** shortcuts were added for stuck or killed services.
- **A gateway restart bug was fixed.** After a Shutdown/Start cycle the socket
  could bind while the old, cancelled coroutine scope meant no request was ever
  accepted. Each start now gets a fresh scope, so the browser immediately loads
  the dashboard again. Startup errors now spell out when port 20128 is busy.
- **Settings was rebuilt as sections**: Gateway, Routing, Token savers, Access &
  API keys, App updates, and About. The account strategy is now a two-choice
  selector with a sticky-limit stepper, token-saver levels appear only when
  enabled, API keys can be copied individually, and preferences can be reset
  without touching providers or connections.
- **Navigation polish**: the current tab and chat state survive rotation, the
  selected tab has a highlighted icon, and the top bar shows the active section
  and gateway status.
- **More parity on the management API**: `/api/provider-nodes` now uses the
  upstream `{ "nodes": [...] }` shape and supports `PUT`/`DELETE` on
  `/api/provider-nodes/{id}`. Node model ids appear in `/v1/models`, custom
  nodes resolve bare model ids, and embeddings URLs are built correctly for
  nodes that store a full chat endpoint.
- **OAuth callbacks are handled on new intents as well as app creation**, and
  the code exchange/post-processing now runs off the main thread.
- **Node API keys are encrypted at rest** with the Android Keystore like every
  other secret.
- **Local HTTP endpoints are supported** for LAN inference servers; the add/edit
  dialog warns when a plain-HTTP URL would send an API key unencrypted.

## What changed in 0.1.9

- **Update prompt on launch.** When a newer release is found, a dialog shows the
  version and release notes with **Update** / **Later**. "Later" is remembered
  per version, so it does not nag; the top-bar pill and Settings panel still
  offer the update.
- **Last-checked time** is shown in Settings → App updates.

## What changed in 0.1.8

- **In-app updates.** The app now checks the GitHub release channel on launch
  (at most every 6 hours) and from **Settings → App updates**. When a newer
  `android-v*` release exists it shows the version and release notes, downloads
  the APK in the app with a progress bar, verifies the package name and ZIP
  header, and opens the system installer through a FileProvider.
  - A compact **Update** pill appears in the top bar while an update is
    available, downloading, or downloaded; tapping it opens Settings.
  - Android's "install unknown apps" permission is requested the first time;
    the app opens the correct system screen for you.
  - The APK is signed with the same debug key as previous releases, so it
    replaces the installed build in place.
- New permission: `REQUEST_INSTALL_PACKAGES`; new FileProvider
  (`${applicationId}.fileprovider`) exposing only the update cache.

## What changed in 0.1.7

- **System Back now navigates instead of closing the app.** In order:
  1. an open provider detail returns to the provider catalog;
  2. Basic Chat closes and returns to the previous tab;
  3. any non-Endpoint tab returns to Endpoint;
  4. only then does Android finish the activity.
- Open dialogs keep their normal behaviour: Back dismisses the dialog first.

## What changed in 0.1.6

- **Cookie connections now send the cookie.** A Web Cookie connection is sent as
  the `Cookie` header instead of a bearer token.
- **Web-session providers are labelled honestly.** The provider page explains
  that some web providers need browser-only attestation (DeepSeek proof-of-work,
  ChatGPT web sockets, etc.) that the Android build does not reproduce, so a
  cookie may not work for every one of them.

## What changed in 0.1.5

- **Claude streaming now works.** Claude SSE (`content_block_delta` /
  `content_block_start`) is translated to OpenAI chunks, so streaming chat with
  Claude API keys or Claude OAuth produces text instead of an empty stream.
- **Claude OAuth sends the Claude Code identity prompt**, which Anthropic
  requires for OAuth access tokens.

## What changed in 0.1.4

- Edge-to-edge fixes for Android 15: the display-cutout mode is now applied in
  code for API 28+, and the API-27-only theme attributes moved out of the base
  theme. `./gradlew :app:lintDebug` now passes with no errors.
- Added a `monochrome` layer so the launcher icon participates in Android 13+
  themed icons.

## Provider transports and OAuth (0.1.3)

- **OAuth logins now work end to end, not just the token exchange.** The
  upstream transports for the OAuth providers were ported:
  - Gemini CLI and Antigravity now use the Cloud Code Assist envelope
    (`{ project, model, request }` plus `:generateContent` /
    `:streamGenerateContent`) and fetch the project id with `loadCodeAssist`.
  - Codex and Grok CLI now speak the Responses API for real (OpenAI Chat →
    `input`/`instructions`, Responses SSE → OpenAI chunks) and send the client
    identity headers those backends require. Codex also carries the
    `ChatGPT-Account-ID` claim from the OAuth id token.
  - Claude API-key connections use `x-api-key`; Claude OAuth keeps the
    Bearer + Claude Code beta headers.
  - Provider-declared transport headers and URL suffixes (`?beta=true`, Kimi,
    MiniMax, Grok identity) are applied from the exported registry.
- **Models and account ids are preserved.** OAuth connections store the Google
  project id, account email, and ChatGPT account id, and the connection card
  shows the account.
- **Priority is editable.** Use ▲ / ▼ on a connection to change its position in
  the fill-first / sticky round-robin order.
- Minor: the provider detail connection card shows the signed-in email and the
  gateway version string is centralised.

## What changed in 0.1.2

- **The gateway starts with the app.** A new *Start gateway when app opens*
  setting (on by default) brings the loopback service up on launch, so
  `http://127.0.0.1:20128/v1` is live without tapping **Start** first.
- **The provider page matches the upstream dashboard.** Providers are grouped
  into OAuth, Free, Free Tier, API Key, and Web Cookie sections (plus Custom
  OpenAI/Anthropic-compatible endpoints). Tapping a card opens a provider detail
  page with **Connections** and the full **Models** catalog.
- **OAuth is no longer hidden behind an API key prompt.** Providers offer
  *OAuth login*, *Device code*, or *Paste token* depending on the flow they use,
  and OAuth-only providers no longer show an API key field.
- **Real device-code login** for GitHub, Grok CLI, Kimi, Muse, and MiniMax Code,
  including GitHub's Copilot token exchange and Muse's Model API key mint.
- **The model catalog is complete**, exported from the same source the dashboard
  uses, with a search box on each provider page.
- **`localhost` works regardless of IP version.** The gateway binds IPv4
  `127.0.0.1` and, when available, IPv6 `::1`.
- **A status page and a health check.** `GET /` returns a small HTML status page
  and `/api/health` reports state, version, connections, and model count. The
  Endpoint tab has a **Test** button and surfaces startup errors.

## Highlights

- **Native foreground service** hosting a loopback-only HTTP gateway at
  `http://127.0.0.1:20128/v1`. The app never binds to `0.0.0.0`.
- **Compatible API surface**: `/api/health`, `/v1/models`,
  `/v1/chat/completions` (JSON and SSE), `/v1/messages`, `/v1/responses`,
  `/v1/embeddings`, and `/api/settings`.
- **In-app streaming chat**, so the APK is useful without any external CLI.
- **Compact mobile-first UI built from the upstream design system**: the exact
  dark palette from `src/app/globals.css`, brand orange, grouped provider cards,
  a dense 54 dp bottom navigation, a top-bar chat action, and edge-to-edge
  status/navigation bars that blend into the app surfaces.
- **Original brand assets**: the orange `hub` brand mark from the dashboard
  sidebar is used as the launcher icon and in the app header; the upstream
  provider icon set is bundled for provider cards.
- **Routing core ported from the upstream project**: model/combo resolution,
  aliases, capability-aware ordering, Smart Router selection, account selection
  (`fill-first` and sticky `round-robin`), cooldown-based fallback, 401/403
  token refresh, RTK tool-result compression, caveman/ponytail/custom system
  prompt injectors, and usage estimation when upstream usage is absent.
- **Management API parity (subset)**: `/api/version`, `/api/auth/status`,
  `/api/usage/stats`, `/api/usage/request-logs`, `/api/keys`,
  `/api/providers`, `/api/combos`, `/api/provider-nodes`, and
  `/api/models/alias`, in addition to the `/v1` inference surface.
- **Provider registry**: 151 declarations and their model catalogs exported from
  the repository into JSON assets at build time. A build-time script regenerates
  them; the device never runs JavaScript.
- **Credential management**: API keys and OAuth tokens are encrypted with
  AES-GCM using the Android Keystore before being written to disk.
- **Usage & Analytics**: Overview/Details tabs, Today/24h/7D/30D/60D/All
  periods, overview cards, recent requests, an area chart, provider/model
  breakdowns, and a per-model table with expandable provider detail.
- **Access control**: multiple API keys with per-key model/combo allow-lists,
  compared in constant time.
- **Custom nodes**: add OpenAI-compatible or Anthropic-compatible endpoints
  with a prefix and their own key.

## Install

Download `app-debug.apk` from this release and sideload it:

```bash
adb install -r app-debug.apk
```

Open the app. The gateway starts automatically; copy the API key from the
**Endpoint** tab. Point any OpenAI-compatible client at:

```text
Base URL: http://127.0.0.1:20128/v1
API key:  <copied from the app>
Model:    openai/gpt-5-mini, claude/claude-sonnet-5, or a combo name
```

From a computer connected by USB, forward the port to reach the phone gateway:

```bash
adb reverse tcp:20128 tcp:20128
```

Android 13+ will ask for notification permission; it is used only by the
persistent foreground service notification.

## Security notes

- Cleartext traffic is allowed because user-added nodes may be local HTTP
  servers (Ollama, LM Studio, etc.). All built-in providers are HTTPS and the
  app warns when a non-loopback HTTP endpoint is entered.
- Secrets are never stored as plaintext in `appstate.json`, including custom
  node API keys.
- Forwarded-header spoofing is avoided: there is no `X-Forwarded-For` trust
  path in this gateway.
- This is a **debug-signed** APK intended for sideloading. A Play Store build
  would need a dedicated release keystore and signing configuration.

## Known limitations

This is a native slice, not full parity with the Node gateway:

- No TLS/MITM interception, certificate injection, or Antigravity IDE
  impersonation.
- Native binary/protobuf envelopes (Kiro EventStream, Cursor protobuf,
  commandcode NDJSON) are not implemented.
- OAuth flows that need provider-specific cryptography (Xiaomi MiMo ECDH) or a
  custom binary handshake (Cursor, Windsurf, Trae, Qoder) use the manual token
  paste path.
- No cloud sync, TTS, STT, image, or video endpoints. `/v1/embeddings` is
  supported for OpenAI-compatible upstreams.
- Translator coverage focuses on OpenAI, Claude, Gemini, and Responses.
  Thinking-block and tool-id edge cases are still being ported.
- Streaming on `/v1/responses` currently emits Chat Completions-style chunks.
- Combo strategies `fallback`, `round-robin`, and `smart` are available;
  `fusion` (parallel panel + judge) is not yet ported.

## Build information

- Version: `0.1.11-android` (version code 13)
- Minimum SDK: 26 (Android 8.0)
- Target SDK: 35 (Android 15), compiled with SDK 36
- Source branch: `master`
- SHA-256 of the attached APK:
  `7584c8af000a759887eeee626d99dcb702f31cd8592218f4feb31ada682a8ead`

See `android/README.md` for the module layout and build instructions.
