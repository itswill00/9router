<div align="center">
  <img src="./public/favicon.svg" alt="9Router" width="72" height="72" />

  # 9Router for Android

  **A native Android gateway for 9Router. No Termux. No Node.js. No shell. No root.**

  Kotlin · Jetpack Compose · OkHttp · Android Keystore · MIT License
</div>

---

## Overview

This branch turns 9Router into a self-contained Android application. The routing
gateway runs as a foreground service on the device and exposes an
OpenAI-compatible endpoint on loopback:

```text
http://127.0.0.1:20128/v1
```

The app is a native port of the upstream 9Router concepts: provider registry,
model and combo resolution, account fallback, token refresh, format translation,
RTK token compression, and usage tracking. The device never runs JavaScript or
bundles a Linux userland.

> **Status: preview.** The inference path and core dashboard are usable, but this
> is not yet full parity with the Node gateway. See
> [Known limitations](#known-limitations).

## Highlights

- **Native foreground service** hosting a loopback-only HTTP gateway.
- **OpenAI-compatible API** for chat completions, Claude Messages, Responses,
  embeddings, and models.
- **Streaming chat** built into the app, so no external CLI is required.
- **Provider registry** exported from the upstream repository at build time.
- **Routing core**: model aliases, capability-aware ordering, combos
  (`fallback`, sticky `round-robin`, `smart`), multi-account rotation, cooldown
  fallback, 401/403 token refresh, and usage tracking.
- **Token savers**: RTK tool-result compression and optional caveman/ponytail
  system-prompt injectors.
- **Credential management**: API keys and OAuth tokens encrypted with AES-GCM
  via the Android Keystore.
- **Login flows**: OAuth PKCE and device-code login, or paste a token manually.
- **Access control**: multiple API keys with per-key model/combo allow-lists.
- **Custom nodes**: OpenAI-compatible and Anthropic-compatible endpoints.
- **Compact mobile UI** using the upstream dark palette and original brand mark.

## Install

Download the debug APK from the latest GitHub release and sideload it:

```bash
adb install -r app-debug.apk
```

Or open **Releases** on GitHub and install the APK on the device. Android 13+
asks for notification permission; it is used only by the persistent foreground
service notification.

## First run

1. Open **9Router**.
2. Tap **Endpoint → Start gateway**.
3. Copy the API key from the Endpoint screen.
4. Add a provider on the **Providers** tab (API key, OAuth, or custom node).
5. Optionally create a **Combo** on the Combos tab.
6. Point a client at the endpoint:

```text
Base URL: http://127.0.0.1:20128/v1
API key:  <key copied from the app>
Model:    openai/gpt-5-mini, claude/claude-sonnet-5, or a combo name
```

The in-app **Basic Chat** action verifies the full path without any external
client.

## API surface

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/health` | Liveness probe |
| `GET` | `/v1/models` | Provider models and combos |
| `POST` | `/v1/chat/completions` | OpenAI Chat Completions (JSON + SSE) |
| `POST` | `/v1/messages` | Claude Messages compatibility |
| `POST` | `/v1/responses` | OpenAI Responses compatibility |
| `POST` | `/v1/embeddings` | OpenAI-compatible embeddings |
| `GET/POST/PATCH` | `/api/settings` | Gateway settings |
| `GET` | `/api/version` | Version metadata |
| `GET` | `/api/auth/status` | Authentication state |
| `GET` | `/api/usage/stats` | Aggregated usage |
| `GET` | `/api/usage/request-logs` | Recent request log |
| `GET/POST` | `/api/keys` | API key list and creation |
| `GET/POST` | `/api/providers` | Provider connections |
| `GET/POST` | `/api/combos` | Combos |
| `GET/POST` | `/api/provider-nodes` | Compatible nodes |
| `GET/POST` | `/api/models/alias` | Model aliases |
| `POST` | `/api/version/shutdown` | Stop the gateway |

All management endpoints require the gateway API key when
`requireApiKey` is enabled.

## Architecture

```text
Compose UI
  ↓
GatewayService (foreground service)
  ↓
Gateway.kt (loopback HTTP + API routes)
  ↓
ChatEngine.kt
  ├── ModelResolver / Catalog (provider registry asset)
  ├── Translator (OpenAI ↔ Claude ↔ Gemini ↔ Responses)
  ├── Rtk / PromptInjectors (token savers)
  ├── Fallback / combo ordering
  └── Upstream.kt (OkHttp, SSE, token refresh)
  ↓
Store.kt (JSON state) + Crypto.kt (Android Keystore AES-GCM)
  ↓
Provider APIs
```

The Android module mirrors the upstream flow:

| Upstream | Android |
|---|---|
| `src/sse/handlers/chat.js` | `core/ChatEngine.kt` |
| `open-sse/handlers/chatCore.js` | `core/ChatEngine.kt` + `server/Gateway.kt` |
| `open-sse/translator/*` | `core/Translator.kt` |
| `open-sse/services/accountFallback.js` | `core/Fallback.kt` |
| `open-sse/rtk/*` | `core/Rtk.kt` + `core/PromptInjectors.kt` |
| `open-sse/executors/*` | `core/Upstream.kt` |
| `src/lib/db/*` | `data/Store.kt` + `data/Crypto.kt` |
| `src/app/(dashboard)/*` | `ui/*` |

## Repository layout

```text
android/
  app/src/main/java/com/ninerouter/android/
    MainActivity.kt        Activity + OAuth callback
    GatewayService.kt      Foreground service
    core/                  Routing, translation, fallback, token savers
    data/                  State, provider catalog, Keystore crypto
    server/                Loopback HTTP server + management API
    ui/                    Compose theme, shell, screens, components
  app/src/main/assets/
    provider_registry.json Exported upstream provider catalog
    providers/             Upstream provider icons
scripts/export-android-config.mjs
```

The rest of the repository is the upstream 9Router project. Android work is
isolated under `android/` so upstream changes remain mergeable.

## Build from source

Requirements:

- JDK 17 or newer
- Android SDK 35
- Android Gradle Plugin 8.9 / Gradle 8.11

```bash
cd android
./gradlew :app:assembleDebug
```

The APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Regenerate the provider catalog after changing the upstream registry:

```bash
node scripts/export-android-config.mjs
```

## Security model

- The gateway binds to `127.0.0.1` only. The app never binds `0.0.0.0`.
- Cleartext HTTP is permitted only for loopback via
  `res/xml/network_security_config.xml`; upstream calls remain HTTPS.
- API keys are compared in constant time.
- Provider secrets are AES-GCM encrypted with the Android Keystore before being
  written to `appstate.json`.
- No `X-Forwarded-For` trust path exists in this gateway.
- The debug APK is intended for sideloading. A Play Store release requires a
  dedicated release keystore and signing configuration.

## Known limitations

This is a native preview, not full parity with the Node gateway:

- No TLS/MITM interception, certificate injection, or Antigravity IDE
  impersonation.
- Native binary/protobuf envelopes (Kiro EventStream, Cursor protobuf,
  commandcode NDJSON) are not implemented.
- No cloud sync, image, TTS, STT, or video endpoints. Embeddings are supported
  for OpenAI-compatible upstreams.
- Translator coverage focuses on OpenAI, Claude, Gemini, and Responses.
  Thinking-block and tool-id edge cases are still being ported.
- Streaming on `/v1/responses` currently emits Chat Completions-style chunks.
- Combo strategy `fusion` (parallel panel + judge) is not ported.
- The desktop-only sections of the upstream dashboard (MITM, tunnel, proxy-pool
  editor, media providers, SSO) are not in the mobile UI.

## Upstream and license

This project is based on [9Router](https://github.com/decolua/9router) by
decolua and contributors, licensed under the MIT License. See [LICENSE](LICENSE).

The Android port keeps the upstream provider registry and design tokens, but
the runtime is an independent Kotlin implementation.
