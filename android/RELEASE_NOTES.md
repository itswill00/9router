# 9Router Android 0.1.24

Native Android build of the 9Router routing gateway. It is written in Kotlin and
runs entirely on the device. **No Termux, no Node.js, no shell, no root, and no
bundled Linux userland are required.**

## What changed since 0.1.23

- **Chat session button card.** The session-list action now uses the same
  surface, border and rounded shape as the model selector and New chat
  buttons instead of a bare icon.

## What changed in 0.1.23

- **Live pages really refresh.** Each screen now reads the live revision in
  its own composition scope, so Usage, Console Log, Quota Tracker and the
  other state-backed pages update while open. The previous helper only
  recomposed itself, which is why logs still needed an app restart.
- **Easier drawer button.** The hamburger is 56 dp, moved inward from the
  system gesture edge, brighter, clears text focus before opening, and the
  top bar is drawn above clipped page content.
- **Basic Chat model picker.** Chat now uses the same provider-grouped picker
  as combos: long ids wrap, provider prefixes are shown once, and aliases are
  included.

## What changed in 0.1.22

- **Combo parity.** Each combo now has its own strategy: Fallback, Round
  Robin, Smart Router or Fusion. Create and edit only the name and ordered
  models; the strategy is changed on the combo card.
- **Smart Router policies.** Smart Switch keeps a session-sticky model and
  only switches at 80% confidence or higher; Strict Lock picks once;
  Per-Turn re-evaluates every request; Custom sets the threshold.
- **Fusion.** Panel models run in parallel without tools, then a judge
  synthesizes one answer. Pick a judge or let the first model do it.
- **Capacity Adapter.** Global Vision and Audio pools switch in a capable
  model when no combo member can read the input. Optional round-robin.
- **Provider-grouped model picker.** Long model ids wrap instead of being
  truncated; group headers show the provider prefix; custom ids can be added;
  multi-select is used by combos and adapter pools.
- **Live screens.** Usage, Console Log, Quota Tracker, Providers, Settings,
  Combos, Media, Endpoint and Chat subscribe to a StateFlow store version.
  Usage and Console Log have a Live indicator and heartbeat; Quota countdowns
  tick every second. New usage appears without restarting the app.
- **Settings rebuilt.** Accented sections, a max-width tablet layout, account
  and combo stickiness steppers, combo/token-saver shortcuts, per-key copy
  and confirmed delete, build information, usage count and confirmed clear.
- **Less duplicate UI.** Repeated page titles and notes were removed; Endpoint
  has one Copy config action instead of a second configuration panel; combo
  cards no longer repeat the strategy label.

## What changed in 0.1.21

- **Sidebar parity.** The drawer now matches the full 9Router navigation:
  Endpoint & Key, Providers, Combo & Vision Adapter, Usage, Quota Tracker,
  Token Saver, CLI Tools, plus a System group with Media Providers, Proxy
  Pools, Skills, Console Log, Translator, 9Remote, 9English and Settings.
  Basic Chat stays out of the drawer, as upstream, and opens from the top-bar
  chat button.
- **Media Providers accordion.** Embedding, Text to Image, Video, Text To
  Speech, Speech To Text, System One and Web Fetch & Search each open a page
  with the gateway route and the providers that advertise the capability.
- **Quota Tracker.** Shows per-account availability, cooldown countdown, local
  seven-day requests/tokens/cost, the last error, and an enable switch.
- **Token Saver page.** RTK compression, Caveman, Ponytail and the custom
  system prompt moved from Settings to their own page.
- **CLI Tools.** Copy-ready snippets for Claude Code, Codex, Cline/Roo, Cursor,
  Continue and OpenCode, with the API key masked in the UI.
- **Skills and Translator.** Skill links can be copied or opened on GitHub, and
  the Translator page previews request bodies between OpenAI, Claude, Gemini
  and Responses formats.
- **Console Log label.** The request log is now named Console Log to match the
  dashboard.
- **External shortcuts.** 9Remote (HOT) and 9English open in the browser.

## What changed in 0.1.20

- **Chat sessions persist.** Leaving Basic Chat and coming back no longer wipes
  the conversation. Sessions are saved in app state, restored on return and on
  app restart, and the partial answer is kept if you leave mid-stream.
- **Session menu.** The chat header has a session list with titles derived from
  the first prompt, message counts, relative time, one-tap switching, a new
  chat action, and delete with image cleanup. Up to 40 sessions are kept.
- **Image attachments for vision models.** Attach up to four photos from the
  composer. Images are downsampled, compressed and stored on disk, then sent as
  OpenAI image parts and translated to Claude, Gemini and Responses formats.
  Thumbnails appear in the composer and in the user bubble, and the composer
  warns when the selected model is not marked vision-capable.
- **OAuth chat streams fixed.** Basic Chat was forcing the OpenAI stream
  decoder even for Claude, Gemini and Antigravity, so those OAuth providers
  rendered "(no content returned)". It now uses the provider's real stream
  format and CLI envelope; a stream request that comes back as JSON is consumed
  as a normal response instead of zero SSE lines.
- **Safer image decode.** Bounds are read first and the bitmap is downsampled,
  so a full-resolution phone photo cannot exhaust memory.

## What changed in 0.1.19

- **Hamburger navigation drawer.** The top-bar hamburger opens a labelled
  drawer with Basic Chat, Endpoint, Providers, Combos, Usage, Logs and
  Settings; choosing an item closes it. Predictive back closes the drawer
  first, then chat, then returns to Endpoint.
- **Provider detail rebuilt.** Each connection is its own card: account and
  toggle, email/project with ellipsis, priority controls and a delete action
  behind a confirmation dialog. OAuth stays the primary action; pasting a
  token is now an outlined secondary action.
- **Model testing for every provider.** Every model row has Test and the
  Models header has Test all (max 3 in parallel, cancellable), including OAuth
  providers such as Antigravity. Probes reuse the real Upstream and Translator
  path, so OAuth headers, CLI envelopes and expired-token refresh apply; the
  request is "hi" with a tiny token budget and a 20 s timeout. Success shows a
  green check and latency, failure opens a copyable error. All / Working /
  Failed filters make results scannable. Tokens are never displayed or logged.
- **Single, faster release artifact.** Releases attach only the release APK.
  R8 is disabled because it dominated build time on the 2-core machine; the
  release build is plain, keeps the sideloading signature for in-place
  updates, and is larger. Debug APKs stay local and are never published.

## What changed in 0.1.18

This is the navigation pass: the bottom bar is gone and every destination lives
in a persistent left sidebar.

- **Sidebar navigation.** Basic Chat sits at the top, separated from Endpoint,
  Providers, Combos, Usage, Logs and Settings. Compact widths show a 76 dp
  icon+label rail; windows 600 dp and wider get a 232 dp labelled sidebar that
  collapses with one tap. The brand and gateway state stay visible, and the top
  bar keeps the section name, status and update pill.
- **Predictable back.** Predictive Back still closes Basic Chat first, then
  returns any other section to Endpoint, and only then leaves the app.
- **Visible interaction feedback on dark surfaces.** The theme now provides a
  light content colour, so ripples and focus rings are legible instead of
  painting a dark scrim. The update pill uses the clipped clickable surface so
  its feedback follows the rounded shape.
- **Larger touch targets.** Sidebar items, the collapse control and the chat
  back button are at least 48 dp, with the rail's compact items at 52 dp.

## What changed in 0.1.17

This is the local API-parity pass: the loopback gateway is now safer for real
clients, and streaming speaks the client's own protocol.

- **Model discovery endpoints.** `GET /v1`, `/v1/models`, `/v1/models/{kind}`,
  `/v1/models/{provider}/{model}` and `/v1/models/info` now serve a full catalog
  (providers, custom nodes, combos, aliases, capability kinds), filtered by key
  access.
- **Restricted API keys are actually enforced.** Chat, embeddings, discovery and
  `count_tokens` share one constant-time authorization policy. A combo grant no
  longer leaks its members as directly callable models.
- **Native streaming protocols.** `POST /v1/messages` now emits Anthropic
  `message_start` / content blocks (text, thinking, tool use) / `message_delta`
  with `stop_reason` + usage / `message_stop`. `POST /v1/responses` emits
  `response.created` … `output_text.delta` … `response.completed` (or
  `response.incomplete` on a length cutoff). Neither emits the OpenAI `[DONE]`
  sentinel, and reasoning/tool deltas survive the translation.
- **Safer HTTP layer.** Request bodies are parsed with hard bounds (8 MiB,
  `Content-Length` or `chunked`, no conflicting framing), preflight `OPTIONS`
  returns a validated CORS response, and JSON errors carry consistent headers.
- **Embeddings and token utilities.** Supported embedding fields (`input`,
  `dimensions`, `encoding_format`, `user`) reach the upstream and its status/body
  are passed through. `POST /v1/messages/count_tokens` returns an approximate
  Anthropic input count, and `POST /v1/responses/compact` fails explicitly with
  `501 unsupported_operation` instead of silently running a normal completion.
- **Two installable variants.** Debug and R8-minified release APKs are both
  signed with the same key and published as `9Router-<version>-<variant>.apk`;
  the in-app updater prefers the release variant.
- **Honest coverage.** `android/API_COMPATIBILITY.md` marks every `/v1` route
  supported, partial or unsupported — no full-parity claim.
- **One version source.** `AppInfo` now derives from Gradle `BuildConfig`, so
  the gateway banner, settings, OAuth headers and update channel cannot drift
  from the APK's real version.

## What changed in 0.1.16

- **Usage now includes streaming requests.** Most API clients stream by default;
  those requests were previously absent from Usage because tracking only ran
  after a non-stream JSON response. The gateway now records usage when the SSE
  body is consumed, preferring upstream token metadata and estimating from the
  request and streamed text when metadata is absent.
- **Failures are logged too.** Upstream HTTP failures and request validation
  errors now create failed entries with status, endpoint and error details.
- **New Logs tab.** Browse recent requests with success/failure filters, stream
  mode, status code, model/provider, token counts, latency and estimated cost.
  Tap an entry for details or copy it. Clearing logs also clears Usage history,
  because both screens use the same persisted request history.
- **Richer local log API.** `/api/usage/request-logs` now returns endpoint,
  stream mode, latency, cached tokens, estimated cost, status code and error.
  `/api/usage/stats` adds stream count and average latency.
- **Existing history stays compatible.** New log fields have safe defaults, so
  older saved usage entries continue to load.
- **SSE framing fixed for OpenAI-compatible clients.** Every streamed response
  now ends with a terminal `finish_reason` chunk (`stop`, `length`,
  `tool_calls`, or `content_filter`) before `data: [DONE]`, even when the
  upstream omits it or the translator drops a content-less terminal frame.
  This stops clients such as OpenCode from treating a finished answer as a
  failed stream and retrying forever.
- **Reasoning and tool-call deltas survive.** OpenAI-format streams now forward
  `reasoning_content` / `reasoning`, `tool_calls` / `function_call`, and the
  initial `role`, so reasoning-only turns and tool use are no longer blanked
  out.

## What changed in 0.1.15

This is the motion pass: the app is no longer stiff, and chat now reports the
model that actually answered plus real usage figures.

- **Chat shows the model that answered.** Each assistant bubble is labelled
  with the resolved provider/model (the combo member that actually replied),
  not a generic app name.
- **Chat shows response stats.** Input and output tokens, tokens/second,
  total latency, and an estimated cost per message. While streaming, the bubble
  shows live tokens, live tok/s and elapsed time.
- **Chat motion.** Bubbles fade and slide into the list, grow smoothly as text
  streams in, the typing indicator bounces, the copy icon turns into a check,
  and the send button colour animates between send/stop/disabled.
- **Navigation motion.** Tabs cross-fade and slide in the direction of travel,
  the bottom bar slides away while chat is open, the nav pill and icon tints
  animate, and the top-bar chat/back actions scale in and out.
- **Provider catalog.** Search now has a leading icon and an animated clear
  button, every section header shows a count pill, list rows animate in place,
  and catalog → detail → node navigation uses a shared slide/fade transition.
- **Detail screens.** Connection and model cards resize smoothly; expandable
  sections animate open and closed.
- **Usage & Settings.** Stat values cross-fade when the period changes, usage
  bars grow from zero, expandable model rows animate, and conditional settings
  fields expand/collapse instead of popping.
- **Faster builds.** The Gradle daemon is now reused with configuration cache,
  build cache and file watching enabled. Warm incremental builds dropped from
  roughly 1m30s to about 15s on the 2-core/8GB build machine.

## What changed in 0.1.13

This is a UI/UX pass: the whole app now follows one 9Router visual system and
every primary screen was reworked for mobile comfort.

- **Provider detail pages rebuilt 9router-style.** A large header sits outside
  the cards (icon, provider name, connection count, auth mode, website link),
  followed by a single Connections card with divided rows, an empty state, and
  full-width add actions. The Models card has search plus incremental rows.
- **Custom node detail rebuilt.** Header, an Endpoint card with a copyable mono
  URL, a Models card with add / fetch / test / remove, and a danger-zone card.
- **Chat page redesigned end to end.** Model selector pill with a searchable
  picker, one-tap new chat, a branded empty state with suggestion cards,
  distinct user/assistant bubbles, per-message copy, an animated typing
  indicator, a rounded composer, and a stop button that cancels the stream.
- **Endpoint page rebuilt.** Gateway status hero with a port pill, copyable
  mono URLs, a masked API key you can reveal/regenerate, and a compact client
  configuration block.
- **Combos reworked.** Strategy chips (fallback / round-robin / smart),
  numbered model rows with divided lines, an empty state, and a smart-only
  router-model field.
- **Usage and Settings polished.** Bordered stat cards, empty states, a
  status-dot gateway row, and aligned action buttons.
- **One design system.** 4/8dp spacing rhythm, 16/18dp card radii, a refined
  dark palette, custom typography, and shared Panel / Pill / EmptyState /
  MonoText / NoticeBanner components. A local copy icon keeps the app on
  `material-icons-core`.

## What changed in 0.1.12

OAuth was audited provider by provider against `src/lib/oauth/providers/*.js`.

- **Antigravity was using the Gemini client id.** It now uses the real
  Antigravity Google client, numeric Code Assist metadata, and the
  `onboardUser` call when `loadCodeAssist` returns no project.
- **PKCE is no longer sent to providers that do not use it.** Claude, Codex,
  xAI and MiniMax use PKCE; Gemini, Antigravity and iFlow do not. Sending
  `code_verifier` to Google is no longer done.
- **Claude/Codex token refresh** now respects the registry refresh encoding and
  scope. Claude refreshes as JSON, Codex sends `openid profile email
  offline_access` again.
- **xAI browser OAuth was added** (PKCE, fixed port 56121, `127.0.0.1`
  callback, nonce/plan/referrer).
- **Nous device OAuth was added**: device code flow plus the `/agent-key`
  mint, with its inference base URL restored.
- **Kimi device OAuth now keeps its device id** and sends the `X-Msh-*` headers
  on both inference and refresh.
- **MiniMax OAuth now sends `audience`, normalizes `expired_in`/interval, and
  sends the MiniMax session/timezone headers on inference.**
- **GitHub Copilot exchange** uses `Bearer` and stores the GitHub email.
- **Grok CLI** uses the correct `grok-pager/1.0.44` user agent and
  `referrer=grok-build`.
- **Muse key minting retries 429/5xx**, and Muse no longer attempts a Meta
  refresh grant that always fails.
- **OAuth post-processing runs off the main thread**, so Google project
  discovery and key minting cannot ANR.
- **Token responses** now accept form-encoded bodies as well as JSON.
- **Unsupported custom OAuth providers** say why (Cline callback, CodeBuddy
  state polling, Cursor import, Kiro IDC, etc.) instead of a generic message.

## What changed in 0.1.11

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
  with `proguard-android-optimize.txt` and the standard kotlinx.serialization
  keep rules.
- **Unit tests.** JVM tests cover custom-endpoint URL building and OAuth
  provider capability rules.

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
- **Compatible API surface**: `/api/health`, `/v1`, `/v1/models` (kind filters,
  lookup and info), `/v1/chat/completions` (JSON and SSE), `/v1/messages` and
  `/v1/responses` (JSON and native SSE), `/v1/embeddings`,
  `/v1/messages/count_tokens`, and `/api/settings`. See
  `android/API_COMPATIBILITY.md` for partial and unsupported routes.
- **In-app streaming chat**, so the APK is useful without any external CLI.
- **Compact mobile-first UI built from the upstream design system**: the exact
  dark palette from `src/app/globals.css`, brand orange, grouped provider cards,
  a hamburger navigation drawer, and edge-to-edge
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

Download `9Router-0.1.24-release.apk` and sideload it:

```bash
adb install -r 9Router-0.1.24-release.apk
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
- The published release APK keeps the sideloading signing identity so in-app
  updates install in place. A Play Store build would need a dedicated release
  keystore and signing configuration.

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
  supported for OpenAI-compatible upstreams. Media Providers pages list the
  bundled registry and gateway routes, but only embeddings has a native route.
- Proxy Pools is informational only; outbound proxy transport is not ported.
- Translator coverage focuses on OpenAI, Claude, Gemini, and Responses.
  Thinking-block and tool-id edge cases are still being ported.
- `/v1/responses/compact` is explicitly unsupported (`501 unsupported_operation`)
  because it needs Codex server-side compaction. Native streaming itself is
  supported for `/v1/messages` and `/v1/responses`.
- Combo strategies `fallback`, `round-robin`, `smart` and `fusion` are
  available. Fusion waits for the full panel; the upstream quorum-grace
  window is not ported.
- Live pages update on store changes and a 1-2s heartbeat while visible;
  there is no websocket push channel.

## Build information

- Version: `0.1.24-android` (version code 26)
- Minimum SDK: 26 (Android 8.0)
- Target SDK: 35 (Android 15), compiled with SDK 36
- Source branch: `master`
- SHA-256 for the attached APK is recorded in the artifact footer below.

See `android/README.md` for the module layout and build instructions.
