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
- `POST /v1/chat/completions` — JSON **and** SSE streaming.
- `POST /v1/messages` / `/v1/responses` — basic Claude/Gemini/Responses ↔ OpenAI
  conversion (JSON responses; Responses streaming falls back to Chat chunk SSE).
- `POST /v1/embeddings` — forwarded to an OpenAI-compatible embeddings URL.
- Provider registry (151 entries) exported at build time from the repo into
  `app/src/main/assets/provider_registry.json` by `scripts/export-android-config.mjs`.
- Model resolution (`provider/model`, aliases, custom node prefixes, bare-model
  inference), combos (`fallback` / sticky `round-robin`), account selection
  (`fill-first` / sticky `round-robin`), cooldown fallback, token refresh
  (401/403), RTK tool-result compression, usage history.
- OAuth PKCE (browser + `ninerouter://oauth/callback`) and device-code login.
- Custom OpenAI/Anthropic-compatible nodes with per-node API key.
- Multiple API keys with per-key model/combo allow-list, constant-time compare.
- In-app streaming chat, so the APK is usable without any external CLI.
- **Mobile-first dashboard** using the upstream visual language: exact dark
  palette from `src/app/globals.css`, brand orange, the original `hub` brand
  mark, provider icon set bundled from `public/providers`, grouped provider
  cards, and bottom navigation with an overflow menu for secondary sections.
- Dashboard screens: Endpoint, Providers, Combos, Usage, Settings, Basic Chat,
  plus informational views for Console Log, Quota Tracker, MITM, CLI Tools,
  Proxy Pools, Token Saver, Translator, and Skills.
- Secrets are AES-GCM boxed with the Android Keystore before disk write.

## Not in this slice yet

- OAuth PKCE / device-code login flows (tokens can be pasted manually).
- Native Kiro / Cursor / Antigravity / commandcode binary envelopes.
- MITM certificate handling, cloud sync, image/tts/stt/embedding endpoints.
- Full translator parity for thinking blocks, tool-id edge cases, `is_error`.
- LAN exposure. The app never binds `0.0.0.0`; use `adb reverse` or an explicit
  tunnel if another device must reach it.

## Build / install

The debug APK is built and ready at:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb install -r /root/9router/android/app/build/outputs/apk/debug/app-debug.apk
```

To rebuild:

```bash
cd android
./gradlew :app:assembleDebug
```

Release notes for the first GitHub release are in `android/RELEASE_NOTES.md`.

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
