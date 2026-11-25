# 9Router Android — Native Gateway (Preview)

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
- **Mobile-first UI built from the upstream design system**: the exact dark
  palette from `src/app/globals.css`, brand orange, grouped provider cards,
  bottom navigation, and an overflow menu for secondary sections.
- **Original brand assets**: the orange `hub` brand mark from the dashboard
  sidebar is used as the launcher icon and in the app header; the upstream
  provider icon set is bundled for provider cards.
- **Routing core ported from the upstream project**: model/combo resolution,
  account selection (`fill-first` and sticky `round-robin`), cooldown-based
  fallback, 401/403 token refresh, RTK tool-result compression, and usage
  tracking.
- **Provider registry**: 151 declarations exported from the repository into a
  JSON asset at build time. A build-time script regenerates it; the device
  never runs JavaScript.
- **Credential management**: API keys and OAuth tokens are encrypted with
  AES-GCM using the Android Keystore before being written to disk.
- **Login flows**: generic OAuth PKCE through the browser with a
  `ninerouter://oauth/callback` redirect, plus device-code login where the
  provider supports it. Tokens can also be pasted manually.
- **Access control**: multiple API keys with per-key model/combo allow-lists,
  compared in constant time.
- **Custom nodes**: add OpenAI-compatible or Anthropic-compatible endpoints
  with a prefix and their own key.

## Install

Download `app-debug.apk` from this release and sideload it:

```bash
adb install -r app-debug.apk
```

Open the app, tap **Start gateway**, then copy the API key from the Overview
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
- Combo strategies `fallback` and `round-robin` are available; `smart` and
  `fusion` are not yet ported.

## Build information

- Version: `0.1.0-android` (version code 1)
- Minimum SDK: 26 (Android 8.0)
- Target SDK: 35 (Android 15)
- Source branch: `feat/android-native-gateway`
- SHA-256 of the attached APK:
  `81faae3d92314d6cad4271cc952ea44a4da1c296ee72fc3f8c3085a3e4d3c161`

See `android/README.md` for the module layout and build instructions.
