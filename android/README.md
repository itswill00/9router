# 9Router for Android (native)

A first, deliberately small slice of 9Router rewritten as a **pure Android app**:
Kotlin + Jetpack Compose + OkHttp + Android Keystore. **No Termux, no Node.js,
no shell, no root, no bundled userland.**

The gateway runs as a foreground service on loopback by default:

```
http://127.0.0.1:20128/v1
```

Remote access is opt-in on **Endpoint & Key**. It binds a wildcard listener so
private interfaces are reachable, but still drops public source addresses and
accepts only loopback plus RFC1918, link-local, CGNAT and IPv6 ULA peers. The
API key is mandatory in that mode, management routes are key-gated, wildcard
CORS is removed, and failed auth is throttled per peer.

## Project lineage and credits

9Router was created by [decolua](https://github.com/decolua) and is developed
with the [9Router contributors](https://github.com/decolua/9router/graphs/contributors).
This module is a fork and native Android port maintained by
[itswill00](https://github.com/itswill00); it is not the original upstream
project.

| Role | Credit |
|---|---|
| Original 9Router gateway, registry and dashboard | decolua and the 9Router contributors |
| Native Android port and Compose UI | itswill00 |
| License | MIT, copyright (c) 2024-2026 decolua and contributors |

- Upstream: https://github.com/decolua/9router
- Contributors: https://github.com/decolua/9router/graphs/contributors
- Android port: https://github.com/itswill00/9router

## What works in this slice

- Opt-in network mode for LAN and tunnel clients: a wildcard listener that
  still drops public source addresses, accepts only loopback plus
  RFC1918/link-local/CGNAT/tailnet peers, forces the API key, key-gates the
  management routes, removes wildcard CORS, and throttles failed auth per peer.
- `GET /api/health`, `GET /v1/models`, `GET|POST /api/settings`.
- API-key auth plus per-key model/combo allow-lists. Management routes require
  the primary key when auth is on; scoped keys are inference-only.
- `GET /v1/models`, `/v1/models/{kind}`, `/v1/models/{provider}/{model}`, `/v1/models/info`
  model discovery filtered by key access.
- `POST /v1/chat/completions` — JSON **and** OpenAI SSE streaming.
- `POST /v1/messages` / `/v1/responses` — native Claude / Responses streaming
  event framing, plus JSON conversion for Claude/Gemini/Responses ↔ OpenAI.
- `POST /v1/embeddings` — forwarded to an OpenAI-compatible embeddings URL.
- `POST /v1/messages/count_tokens` — approximate Anthropic input token count.
- `/v1/responses/compact` returns a structured 501 (`unsupported_operation`).
  See `android/API_COMPATIBILITY.md` for the full route matrix.
- Provider registry (151 entries) exported at build time from the repo into
  `app/src/main/assets/provider_registry.json` by `scripts/export-android-config.mjs`.
- Model resolution (`provider/model`, aliases, custom node prefixes, bare-model
  inference, aliases, capability ordering), per-combo strategies (`fallback` /
  sticky `round-robin` / `smart` with Smart Switch, Strict Lock, Per-Turn and
  custom thresholds / `fusion` panel + judge), global vision and audio capacity
  adapters, account selection (`fill-first` / sticky `round-robin`), cooldown
  fallback, token refresh (401/403), RTK tool-result compression,
  caveman/ponytail/custom prompt injectors, and usage history.
- OAuth PKCE with a temporary loopback callback server (the same redirect shape
  the desktop app uses) plus device-code login for GitHub, Grok CLI, Kimi, Muse,
  and MiniMax Code, including GitHub Copilot token exchange and Muse key mint.
  Provider-specific transports are ported too: the Cloud Code Assist envelope
  for Gemini CLI / Antigravity, the Responses API for Codex / Grok CLI, Claude
  Code headers, transport-declared headers, and URL suffixes. Form vs JSON token
  encodings, Basic Auth (iFlow), and client secrets are honored.
- Custom OpenAI/Anthropic-compatible nodes with per-node API key.
- Multiple API keys with per-key model/combo allow-list, constant-time compare.
- **Media gateway v1.** `POST /v1/images/generations` (OpenAI-compatible
  providers plus Gemini), `/v1/audio/speech` (OpenAI and ElevenLabs),
  `/v1/audio/transcriptions` (OpenAI-compatible multipart), `/v1/search`
  (Tavily, Exa, Brave, Serper) and `/v1/web/fetch` (Jina Reader, Firecrawl,
  Tavily, Exa). Media pages mark each provider as ready or registry-only and
  include a request console that calls the local gateway.
- In-app streaming chat with persistent sessions, a session menu, image attachments for vision models, and a vision-capability warning.
- **Compact mobile-first dashboard** using the upstream visual language: exact
  dark palette from `src/app/globals.css`, brand orange, the original `hub`
  brand mark, provider icon set bundled from `public/providers`, grouped
  provider cards, a hamburger navigation drawer, and edge-to-edge system bars.
- **Navigation drawer.** The top-bar hamburger opens a drawer that mirrors the
  full 9Router sidebar: Endpoint & Key, Providers, Combo & Vision Adapter,
  Usage, Quota Tracker, Token Saver, CLI Tools, then a System group with
  Media Providers (Embedding, Text to Image, Video, Text To Speech, Speech To
  Text, System One, Web Fetch & Search), Proxy Pools, Skills, Console Log,
  Translator, 9Remote, 9English, About and Settings. Basic Chat stays out of
  the drawer, as it does upstream, and opens from the top-bar chat button.
  Predictive back closes the drawer first, then chat, then returns to Endpoint.
- **Quota Tracker.** Per-account availability, cooldown countdown, local
  seven-day request/token/cost totals, last error, and an enable switch.
- **Token Saver page.** RTK compression, caveman and Ponytail prompts, and a
  custom system prompt now live on their own page instead of inside Settings.
- **CLI Tools.** Copy-ready Claude Code, Codex, Cline/Roo, Cursor, Continue and
  OpenCode snippets for the loopback gateway, with the API key kept masked.
- **Media Providers.** Each visible media kind shows its gateway route and the
  providers that advertise it in the bundled registry; unsupported routes are
  labelled instead of pretending to work.
- **Skills, Translator and Proxy Pools.** Skill links can be copied or opened
  on GitHub, the Translator page previews request bodies between OpenAI,
  Claude, Gemini and Responses formats, and Proxy Pools explains why the
  native build connects directly.
- **About page.** A project lineage that credits decolua as the upstream
  9Router founder and the upstream contributors, alongside itswill00 as the
  Android port author. It carries both profile photos, a 2×2 feature grid,
  tech-stack rows, dynamic counts and source/releases/issues/MIT links.
- **Provider detail polish.** Connections render as individual cards with
  ellipsis-safe account rows, confirmed delete, and per-model Test / Test all
  (OAuth and API key, 20 s timeout, 3 parallel) with Working/Failed filters.
- **Usage & Analytics**: Overview/Details tabs, Today/24h/7D/30D/60D/All
  periods, overview cards, a vertical live provider flow, recent requests, an area
  chart, provider/model breakdowns, and a per-model table with expandable
  provider detail.
- **Live screens.** Usage, Console Log, Quota Tracker, Providers, Settings and
  the other state-backed pages subscribe to the persisted store, so gateway
  traffic and cooldown changes show up while the page is open. Usage and the
  Console Log also carry a small Live indicator and a visible-screen heartbeat;
  Quota Tracker ticks its countdowns every second.
- **Model picker.** Models are grouped by provider, long ids wrap instead of
  being cut off, the provider prefix is shown on the group header, and a custom
  model id can be added inline. Multi-select is used by combos and the vision
  and audio capacity adapters.
- **Settings.** Max-width layout for tablets, accented section headers, account
  and combo round-robin stickiness, token-saver and combo shortcuts, per-key
  copy/delete with confirmation, update channel, build information and a
  confirmed usage-history clear.
- **Languages.** Settings → Language follows the system language until you pick
  one, then offers English, Bahasa Indonesia and the other 33 upstream locales.
  Copy is resolved at runtime from the bundled dictionaries, so switching is
  immediate; untranslated strings fall back to English and RTL locales flip the
  layout.
- **Provider page**: grouped provider sections (OAuth, Free, Free Tier, API Key,
  Web Cookie, Custom compatible endpoints), a provider detail screen with
  **Connections** and the full **Models** catalog (searchable), and per-flow
  connect actions. OAuth-only providers show *OAuth login* or *Device code*;
  API-key providers show *API key*; providers with crypto-specific handshakes
  fall back to *Paste token*. Compatible nodes keep an ordered list of API keys
  with failover/round-robin, and their probes try each key until one works.
- **Model catalog**: model ids and display names are exported from the same
  source the dashboard uses (`getModelsByProviderId`) into
  `provider_registry.json`.
- **Gateway lifecycle**: the loopback service auto-starts on launch
  (*Start gateway when app opens*, default on), binds IPv4 `127.0.0.1` and, when
  available, IPv6 `::1`, and exposes a `GET /` status page plus `/api/health`.
  Startup errors and a **Test** button are shown on the Endpoint tab.
- **In-app updates**: Settings → App updates checks GitHub releases (and silently
  on launch, at most every 6 hours), shows the changelog, downloads the APK with
  progress, verifies it, and opens the system installer through a FileProvider.
- Management API parity (subset): `/api/version`, `/api/auth/status`,
  `/api/usage/stats`, `/api/usage/request-logs`, `/api/keys`,
  `/api/providers`, `/api/combos`, `/api/provider-nodes`, `/api/models/alias`.
- Secrets are AES-GCM boxed with the Android Keystore before disk write.

## Not in this slice yet

- Native Kiro / Cursor / Antigravity / commandcode binary envelopes.
- MITM certificate handling, cloud sync, image/TTS/STT/video endpoints.
- Full translator parity for thinking blocks, tool-id edge cases, `is_error`.
- In-app TLS. A public domain must end at a tunnel or reverse proxy; the app
  never trusts `X-Forwarded-For`.

## Build / install

Build the single release variant and stage it (no R8, no debug APK):

```bash
./scripts/android-build.sh release
```

That produces:

```
android/dist/9Router-<version>-release.apk
```

Install it:

```bash
adb install -r android/dist/9Router-<version>-release.apk
```

The debug variant is for local device testing only and is never published:

```bash
./scripts/android-build.sh apk    # debug APK
./scripts/android-build.sh run    # build + install debug
```

## Release

```bash
./scripts/android-release.sh          # build release + verify + preview (no publish)
./scripts/android-release.sh --yes    # tag + publish the release APK
```

The release title is `9Router Android <version>`, and the notes are generated from
`android/RELEASE_NOTES.md` — its leading `# …` heading is dropped so the release
page does not repeat the name. A single release APK is attached with its
SHA-256. See `android/API_COMPATIBILITY.md` for the full release gate.

Regenerate the provider asset after changing the upstream registry:

```bash
# from repo root
node scripts/export-android-config.mjs
```

## Connect a client

Point any OpenAI-compatible client at:

```
Base URL: http://127.0.0.1:20128/v1
API key:  copied from the app's Endpoint & Key tab
Model:    openai/gpt-5-mini, claude/claude-sonnet-5, or a combo name
```

On the same device, loopback is shared between apps. If a client cannot reach
it, verify with `adb shell curl http://127.0.0.1:20128/api/health`.

### LAN, tailnet and your own domain

Open **Endpoint & Key → Remote access** and turn on *Allow LAN and tunnel
clients*. The app lists the private URLs it is reachable on and requires an API
key. Point a tunnel or reverse proxy (Cloudflare Tunnel, Tailscale, nginx on a
VPS, WireGuard) at the phone, then save its HTTPS URL in **Public base URL**.
The copy-config actions and the status page switch to that URL, and **Test URL**
checks both `/api/health` and an authenticated `/v1/models` through the public
path.

The phone never terminates TLS and never trusts `X-Forwarded-For`. Only the
private hop is plain HTTP; the public hop must end at your proxy. Public source
addresses are rejected even in network mode, so exposing the raw port to the
internet does not work by design.

## Security notes

- Loopback is the default. Remote access is opt-in: the gateway may bind a
  wildcard listener, but the accept loop drops every peer outside loopback,
  RFC1918, link-local, CGNAT (`100.64.0.0/10`) and IPv6 ULA/link-local ranges.
- Network mode always requires the API key even when the local toggle is off,
  key-gates `/api/keys`, `/api/providers`, `/api/combos`, `/api/settings`,
  `/api/usage/*` and shutdown, omits the wildcard CORS origin, and throttles
  repeated failed keys per peer.
- There is no `X-Forwarded-For` trust path and no in-app TLS. Terminate TLS in a
  tunnel or reverse proxy; the phone keeps serving plain HTTP on the private hop.
- API key comparison uses `MessageDigest.isEqual` (constant time).
- `usesCleartextTraffic` is enabled only because the endpoint is HTTP.
- Provider tokens/keys never land in plaintext in `appstate.json`.
- Do not remove the peer allow-list, forced key, or management gate when
  touching the exposure code.

## Origin of the code

This module intentionally mirrors the upstream flow:

| Upstream | Android |
|---|---|
| `src/sse/handlers/chat.js` | `core/ChatEngine.kt` |
| `open-sse/handlers/chatCore.js` | `core/ChatEngine.kt` + `server/Gateway.kt` |
| `open-sse/translator/*` | `core/Translator.kt` |
| `open-sse/services/accountFallback.js` | `core/Fallback.kt` |
| `open-sse/rtk/*` | `core/Rtk.kt` |
| `open-sse/executors/*` | `core/Upstream.kt` |
| `src/lib/db/*` + `localDb.js` | `data/Store.kt` + `data/Crypto.kt` |
| `src/app/(dashboard)/*` | `MainActivity.kt` |

## Credits and license

The upstream gateway, provider registry and dashboard were created by
[decolua](https://github.com/decolua) and are maintained with the
[9Router contributors](https://github.com/decolua/9router/graphs/contributors).
The native Android implementation in this directory is maintained by
[itswill00](https://github.com/itswill00).

Distributed under the [MIT License](../LICENSE). Copyright (c) 2024-2026
decolua and contributors. Keep the upstream copyright notice and credit when
redistributing.
