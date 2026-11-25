# 9Router Android 0.1.1

This is the first installable native Android build of the 9Router routing
gateway. It is written in Kotlin and runs entirely on the device. **No Termux,
no Node.js, no shell, no root, and no bundled Linux userland are required.**

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
- **Provider registry**: 151 declarations exported from the repository into a
  JSON asset at build time. A build-time script regenerates it; the device
  never runs JavaScript.
- **Credential management**: API keys and OAuth tokens are encrypted with
  AES-GCM using the Android Keystore before being written to disk.
- **Login flows**: OAuth PKCE through the browser with a temporary loopback
  callback server (the redirect shape provider client IDs expect), per-provider
  form/JSON token exchange, Google client secrets, and device-code login.
  Tokens can also be pasted manually.
- **Working connection toggles** and a compact two-column provider page:
  connected accounts are listed first, and one connect dialog offers browser
  login, device login, API key, or token paste.
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

Open the app, tap **Start gateway**, then copy the API key from the **Endpoint**
tab. Point any OpenAI-compatible client at:

```text
Base URL: http://127.0.0.1:20128/v1
API key:  <copied from the app>
Model:    openai/gpt-5-mini, claude/claude-sonnet-5, or a combo name
```

Android 13+ will ask for notification permission; it is used only by the
persistent foreground service notification.

## Security notes

- Cleartext traffic is restricted by `network_security_config.xml` to
  `127.0.0.1` and `localhost`. Upstream provider calls remain HTTPS.
- Secrets are never stored as plaintext in `appstate.json`.
- Forwarded-header spoofing is avoided: there is no `X-Forwarded-For` trust
  path in this gateway.
- This is a **debug-signed** APK intended for sideloading. A Play Store build
  would need a dedicated release keystore and signing configuration.

## Known limitations

This is a first native slice, not full parity with the Node gateway:

- No TLS/MITM interception, certificate injection, or Antigravity IDE
  impersonation.
- Native binary/protobuf envelopes (Kiro EventStream, Cursor protobuf,
  commandcode NDJSON) are not implemented.
- No cloud sync, TTS, STT, image, or video endpoints. `/v1/embeddings` is
  supported for OpenAI-compatible upstreams.
- Translator coverage focuses on OpenAI, Claude, Gemini, and Responses.
  Thinking-block and tool-id edge cases are still being ported.
- Streaming on `/v1/responses` currently emits Chat Completions-style chunks.
- Combo strategies `fallback`, `round-robin`, and `smart` are available;
  `fusion` (parallel panel + judge) is not yet ported.

## Build information

- Version: `0.1.1-android` (version code 3)
- Minimum SDK: 26 (Android 8.0)
- Target SDK: 35 (Android 15)
- Source branch: `feat/android-native-gateway`
- SHA-256 of the attached APK:
  `c650070bed1a2dd30c062586f7ffa262d8dd45e3b663cdb998fad9d23dfbd825`

See `android/README.md` for the module layout and build instructions.
