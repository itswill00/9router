# OAuth audit (Android)

Status against `src/lib/oauth/providers/*.js` after the 0.1.12 fixes.

## Implemented end to end

| Provider | Flow | Notes |
|---|---|---|
| claude | Browser PKCE, loopback | Claude Code identity + beta headers; JSON token exchange. |
| codex | Browser PKCE, fixed port 1455 | Responses transport; `ChatGPT-Account-ID` from the id token; refresh scope preserved. |
| gemini-cli | Browser Google OAuth | Correct Google client, numeric Code Assist metadata, project discovery. |
| antigravity | Browser Google OAuth | Antigravity client id/secret (not Gemini's), numeric metadata, `onboardUser` when no project. |
| iflow | Browser code + Basic auth | No PKCE, custom query shape, Basic token refresh. |
| xai | Browser PKCE, fixed port 56121 | Static xAI endpoints, nonce/plan/referrer, `127.0.0.1` redirect. |
| github | Device code | GitHub → Copilot token exchange with `Bearer`, user email. |
| grok-cli | Device code | Correct `grok-pager/1.0.44` user agent and `referrer=grok-build`. |
| kimi | Device code | `X-Msh-*` headers, device id stored on the connection and sent on inference + refresh. |
| muse | Device code | Minted Model API key with 429/5xx retry; no invalid Meta refresh. |
| minimax-code / -global | Device code + PKCE | Audience, `expired_in`/interval normalization, user-code polling, MiniMax session/timezone headers. |
| nous | Device code | Device flow plus agent-key mint; inference base URL restored. |

## Not ported (manual token paste)

These need provider-specific crypto, browser-token formats, or a client id you
must register. The app states the reason on the provider detail page.

`cline`, `clinepass`, `codebuddy-cn`, `codebuddy-intl`, `glm`, `kilocode`,
`qoder`, `qoder-cn`, `gitlab`, `kimchi`, `trae`, `windsurf`, `zed`, `kiro`,
`cursor`, `xiaomi-mimo`.

## Notes

- PKCE is only sent when the provider declares `codeChallengeMethod` (or is a
  MiniMax device flow). Google/antigravity/iflow no longer send an unnecessary
  `code_verifier`.
- Token parsing accepts JSON and `application/x-www-form-urlencoded` responses.
- OAuth post-processing (Google project discovery, Copilot exchange, Muse/Nous
  key minting) runs on an IO dispatcher.
- Custom node/API-key paths are unchanged.
