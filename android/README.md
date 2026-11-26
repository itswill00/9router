# 9Router for Android (native)

A first, deliberately small slice of 9Router rewritten as a **pure Android app**:
Kotlin + Jetpack Compose + OkHttp + Android Keystore. **No Termux, no Node.js,
no shell, no root, no bundled userland.**

The gateway runs as a foreground service and listens on loopback only:

```
http://127.0.0.1:20128/v1
```

## What works in this slice

- Foreground service + loopback HTTP/1.1 server (`Gateway.kt`).
- `GET /api/health`, `GET /v1/models`, `GET|POST /api/settings`.
- API-key auth plus per-key model/combo allow-lists, with CORS preflight for browsers.
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
  inference, aliases, capability ordering), combos (`fallback` / sticky
  `round-robin` / `smart`), account selection (`fill-first` / sticky
  `round-robin`), cooldown fallback, token refresh (401/403), RTK tool-result
  compression, caveman/ponytail/custom prompt injectors, and usage history.
- OAuth PKCE with a temporary loopback callback server (the same redirect shape
  the desktop app uses) plus device-code login for GitHub, Grok CLI, Kimi, Muse,
  and MiniMax Code, including GitHub Copilot token exchange and Muse key mint.
  Provider-specific transports are ported too: the Cloud Code Assist envelope
  for Gemini CLI / Antigravity, the Responses API for Codex / Grok CLI, Claude
  Code headers, transport-declared headers, and URL suffixes. Form vs JSON token
  encodings, Basic Auth (iFlow), and client secrets are honored.
- Custom OpenAI/Anthropic-compatible nodes with per-node API key.
- Multiple API keys with per-key model/combo allow-list, constant-time compare.
- In-app streaming chat, so the APK is usable without any external CLI.
- **Compact mobile-first dashboard** using the upstream visual language: exact
  dark palette from `src/app/globals.css`, brand orange, the original `hub`
  brand mark, provider icon set bundled from `public/providers`, grouped
  provider cards, a hamburger navigation drawer, and edge-to-edge system bars.
- **Navigation drawer.** The top-bar hamburger opens a labelled drawer (Basic
  Chat, Endpoint, Providers, Combos, Usage, Logs, Settings); selecting an item
  closes it. Predictive back closes the drawer first, then chat, then returns
  to Endpoint.
- **Provider detail polish.** Connections render as individual cards with
  ellipsis-safe account rows, confirmed delete, and per-model Test / Test all
  (OAuth and API key, 20 s timeout, 3 parallel) with Working/Failed filters.
- **Usage & Analytics**: Overview/Details tabs, Today/24h/7D/30D/60D/All
  periods, overview cards, recent requests, an area chart, provider/model
  breakdowns, and a per-model table with expandable provider detail.
- **Provider page**: grouped provider sections (OAuth, Free, Free Tier, API Key,
  Web Cookie, Custom compatible endpoints), a provider detail screen with
  **Connections** and the full **Models** catalog (searchable), and per-flow
  connect actions. OAuth-only providers show *OAuth login* or *Device code*;
  API-key providers show *API key*; providers with crypto-specific handshakes
  fall back to *Paste token*.
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
- Combo strategy `fusion` (panel + judge).
- LAN exposure. The app never binds `0.0.0.0`; use `adb reverse` or an explicit
  tunnel if another device must reach it.

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
API key:  copied from the app's Overview tab
Model:    openai/gpt-5-mini, claude/claude-sonnet-5, or a combo name
```

On the same device, loopback is shared between apps. If a client cannot reach
it, verify with `adb shell curl http://127.0.0.1:20128/api/health`.

## Security notes

- Bind is `127.0.0.1` only. There is no `X-Forwarded-For` trust path.
- API key comparison uses `MessageDigest.isEqual` (constant time).
- `usesCleartextTraffic` is enabled only because the endpoint is HTTP loopback.
- Provider tokens/keys never land in plaintext in `appstate.json`.
- Do not add a `0.0.0.0` bind without reintroducing auth + TLS + SSRF review.

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
