# Android API compatibility matrix

The native Android gateway (`android/`) intentionally implements a **subset** of
the desktop 9Router `/v1` surface. Every route below is marked `supported`,
`partial`, or `unsupported`, with the JVM contract test that pins its behavior
where one exists. Nothing here claims full `/v1` parity.

- Base URL: `http://127.0.0.1:20128/v1` by default. Remote access is opt-in:
  network mode binds private interfaces, still drops public source addresses,
  and expects a tunnel or reverse proxy for a public domain.
- Auth: when `requireApiKey` is on, send the gateway key as `Authorization: Bearer …`,
  `X-API-Key`, or `api-key`. Restricted keys only see/call their allow-listed
  models and cannot use management routes.
- CORS: no wildcard origin. The bundled status page and the Android UI are
  same-origin/local, so cross-origin JSON management calls are not supported.
- Request framing: `Content-Length` and `chunked` bodies, bounded at 8 MiB.

## Route matrix

| Desktop route (`src/app/api/v1/**/route.js`) | Android | Notes | Contract test |
| --- | --- | --- | --- |
| `GET /v1` | supported | Model list, key-filtered. | `ModelsApiTest` |
| `GET /v1/models` | supported | `kind` slug/capability filtering; key-filtered. | `ModelsApiTest`, `ApiAccessTest` |
| `GET /v1/models/{kind}` | partial | Slugs: `image`, `tts`, `stt`, `embedding`, `image-to-text`, `web`, `video`. No `llm` slug (use `/v1/models`). | `ModelsApiTest` |
| `GET /v1/models/{provider}/{model}` | supported | Single-model lookup, 404 when filtered out. | `ModelsApiTest` |
| `GET /v1/models/info?id=…&kind=…` | partial | Android shape: list entry plus a `capabilities` string array; upstream also returns endpoint/params/dimensions. | `ModelsApiTest` |
| `POST /v1/chat/completions` | supported | JSON and OpenAI SSE; terminal finish chunk then `[DONE]`. | `SseEncoderTest`, `SseWriterTest` |
| `POST /v1/messages` | supported | Anthropic Messages JSON and native SSE (`message_start` … `message_stop`, no `[DONE]`). | `SseEncoderTest` |
| `POST /v1/responses` | supported | Responses JSON and native SSE (`response.created` … `response.completed`/`incomplete`, no `[DONE]`). | `SseEncoderTest` |
| `POST /v1/messages/count_tokens` | partial | Approximate Anthropic character/4 estimator, not a real tokenizer. | `CoreCompatibilityRoutesTest` |
| `POST /v1/embeddings` | partial | Forwards `input`, `dimensions`, `encoding_format`, `user`; returns upstream status/body. | `CoreCompatibilityRoutesTest` |
| `POST /v1/images/generations` | partial | OpenAI-compatible image providers (OpenAI, MiniMax, OpenRouter, Recraft, xAI, SenseNova, Vercel AI Gateway) plus Gemini. Supports `?response_format=binary`; Gemini responses are normalized to `b64_json`. | `MediaEngineTest` |
| `POST /v1/audio/speech` | partial | OpenAI TTS and ElevenLabs. Returns raw audio by default; `?response_format=json` returns base64 audio. | `MediaEngineTest` |
| `POST /v1/audio/transcriptions` | partial | OpenAI-compatible multipart forwarding for OpenAI and Groq. The provider `model` field is rewritten without its router prefix; 8 MiB request cap. | `MediaEngineTest` |
| `GET /v1/audio/voices` | unsupported | Use the upstream dashboard or provider API. | — |
| `POST /v1/search` | partial | Tavily, Exa, Brave Search and Serper; normalized `{provider,query,results,…}` envelope. | `MediaEngineTest` |
| `POST /v1/web/fetch` | partial | Jina Reader, Firecrawl, Tavily Extract and Exa Contents; normalized title/content envelope. | `MediaEngineTest` |
| `POST /v1/responses/compact` | unsupported | Structured `501 unsupported_operation`; never runs a normal completion. | `CoreCompatibilityRoutesTest` |
| `POST /v1/api/chat` | unsupported | Upstream's Ollama-compatible chat route; Android returns 404. | — |
| `GET|POST /v1beta/models/{model}:*` | unsupported | No native Gemini route; returns 404. | — |
| `/v1/audio/*` (music, voices) | unsupported | TTS/STT are supported; voice discovery and music are not. | — |
| `/v1/systemone` | unsupported | — | — |
| `/v1/videos/*` (generations, edits, extensions, `{id}`) | unsupported | — | — |

### Media provider matrix

| Capability | Native adapters |
| --- | --- |
| Image generation | `openai`, `minimax`, `openrouter`, `recraft`, `vercel-ai-gateway`, `xai`, `sensenova`, `sensenova-intl`, `gemini` |
| Text to speech | `openai`, `elevenlabs` |
| Speech to text | `openai`, `groq` (OpenAI-compatible multipart) |
| Web search | `tavily`, `exa`, `brave-search`, `serper` |
| Web fetch | `jina-reader`, `firecrawl`, `tavily`, `exa` |

`/v1/models/{kind}` hides media entries for providers without a native
adapter, so a client cannot discover a route that would end in a 501. The
remaining registry entries stay visible in the in-app provider list with a
`Registry` badge.

Media requests use the provider registry's declarative endpoint config. No
media request is proxied through a desktop service. Audio and image responses
are streamed straight to the socket; image JSON responses are passed through
unless Gemini needs normalization.

## Streaming protocols

`SseWriter` normalizes every upstream dialect to an OpenAI chat chunk once, then
hands it to the encoder selected by the **client** response format
(`SseEncoder.forFormat`):

- **OpenAI Chat** — `data:` chunks, exactly one terminal `finish_reason` chunk, then one `data: [DONE]`.
- **Anthropic Messages** — `message_start`, thinking/text/tool content blocks with valid `index`/`id`/`name`/`input_json_delta`, `message_delta` (`stop_reason` + usage), `message_stop`. No `[DONE]`.
- **OpenAI Responses** — `response.created`, `response.in_progress`, reasoning/message/tool output items, `response.output_text.delta`, then `response.completed` (or `response.incomplete` on a length cutoff). No `[DONE]`.

Reasoning (`reasoning_content` / `reasoning`) and tool-call deltas are preserved;
`length` maps to Claude `max_tokens` and Responses `max_output_tokens`.
Streamed requests still record usage: `ChatEngine` wraps the response body so
counts are captured through EOF for every format (`ChatEngineStreamUsageTest`).

## Security notes

- Loopback is the default. Network mode is opt-in and binds private
  interfaces only after dropping public source addresses; use a tunnel or
  reverse proxy for a public domain.
- API-key compare is constant-time. Management routes require the primary key;
  scoped keys are inference-only and cannot mint keys or read settings.
- Stored keys are masked in management responses. A newly created key is
  returned exactly once.
- No wildcard CORS: cross-origin JSON management calls are not supported.
- Errors never echo credentials, conversation bodies, or stack traces; unknown routes return generic JSON errors.
- Request bodies are bounded before parsing (`HttpRequestParserTest`).

## Release gate

Releases are cut with `./scripts/android-release.sh` (see `android/README.md`).

1. `./scripts/android-release.sh` builds and verifies the release APK, then prints the plan without publishing. It runs `./scripts/android-build.sh release` (release variant only; R8 is disabled because it dominated build time on 2 cores) and refuses a dirty tree, an existing tag, or mismatched APK metadata.
2. A single `9Router-<version>-release.apk` is staged in `android/dist/` and published. It is a plain release build (no R8) signed with the shared sideloading identity so in-app updates install in place. Its embedded `versionCode`/`versionName` must match `android/app/build.gradle.kts`, and its SHA-256 is recorded in the release notes.
   Debug APKs are local development artifacts and are never attached to a release.
3. The in-app updater selects the `-release` asset, falling back to debug or any `.apk` for older releases (`UpdateManagerAssetTest`).
4. Do not tag or publish until the route coverage above is reviewed and the release intent is confirmed; no device/emulator runtime claim without device evidence.
5. Release title convention: `9Router Android <version>`, and pass the notes body **without** its leading `# …` heading (the script does this). GitHub already renders the title above the body, so repeating the heading duplicates the name on the release page and shows a raw `#` in the in-app updater.
