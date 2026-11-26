# Android API compatibility matrix

The native Android gateway (`android/`) intentionally implements a **subset** of
the desktop 9Router `/v1` surface. Every route below is marked `supported`,
`partial`, or `unsupported`, with the JVM contract test that pins its behavior.
Nothing here claims full `/v1` parity.

- Base URL: `http://127.0.0.1:20128/v1` (loopback only).
- Auth: when `requireApiKey` is on, send the gateway key as `Authorization: Bearer …`,
  `X-API-Key`, or `api-key`. Restricted keys only see/call their allow-listed models.
- CORS: browser preflight (`OPTIONS`) returns `204` with wildcard origin, no credentials.
- Request framing: `Content-Length` and `chunked` bodies, bounded at 8 MiB.

## Route matrix

| Desktop route (`src/app/api/v1/**/route.js`) | Android | Notes | Contract test |
| --- | --- | --- | --- |
| `GET /v1` | supported | Model list, key-filtered. | `ModelsApiTest` |
| `GET /v1/models` | supported | `kind` slug/capability filtering; key-filtered. | `ModelsApiTest`, `ApiAccessTest` |
| `GET /v1/models/{kind}` | supported | e.g. `llm`, `web`, `embedding`. | `ModelsApiTest` |
| `GET /v1/models/{provider}/{model}` | supported | Single-model lookup, 404 when filtered out. | `ModelsApiTest` |
| `GET /v1/models/info?id=…&kind=…` | supported | Model metadata lookup. | `ModelsApiTest` |
| `POST /v1/chat/completions` | supported | JSON and OpenAI SSE; terminal finish chunk then `[DONE]`. | `SseEncoderTest`, `SseWriterTest` |
| `POST /v1/messages` | supported | Anthropic Messages JSON and native SSE (`message_start` … `message_stop`, no `[DONE]`). | `SseEncoderTest` |
| `POST /v1/responses` | supported | Responses JSON and native SSE (`response.created` … `response.completed`/`incomplete`, no `[DONE]`). | `SseEncoderTest` |
| `POST /v1/messages/count_tokens` | partial | Approximate Anthropic character/4 estimator, not a real tokenizer. | `CoreCompatibilityRoutesTest` |
| `POST /v1/embeddings` | partial | Forwards `input`, `dimensions`, `encoding_format`, `user`; returns upstream status/body. | `CoreCompatibilityRoutesTest` |
| `POST /v1/responses/compact` | unsupported | Structured `501 unsupported_operation`; never runs a normal completion. | `CoreCompatibilityRoutesTest` |
| `GET /v1/api/chat` | unsupported | Dashboard-only thread API; not exposed. | — |
| `/v1/audio/*` (speech, transcriptions, voices) | unsupported | No audio pipeline in the native gateway. | — |
| `/v1/images/generations` | unsupported | — | — |
| `/v1/search` | unsupported | — | — |
| `/v1/systemone` | unsupported | — | — |
| `/v1/videos/*` (generations, edits, extensions, `{id}`) | unsupported | — | — |
| `/v1/web/fetch` | unsupported | — | — |

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

- Gateway stays loopback-bound; no Node, Termux, root, or bundled runtime.
- API-key compare is constant-time; restricted keys cannot reach combo members as directly callable models (`ApiAccessTest`).
- Errors never echo credentials, conversation bodies, or stack traces; unknown routes return generic JSON errors.
- Request bodies are bounded before parsing (`HttpRequestParserTest`).

## Release gate

Releases are cut with `./scripts/android-release.sh` (see `android/README.md`).

1. `./scripts/android-release.sh` builds and verifies both variants, then prints the plan without publishing. It runs `./scripts/android-build.sh full` (unit tests + full lint + debug + R8 release) and refuses a dirty tree, an existing tag, or mismatched APK metadata.
2. Two APKs are staged in `android/dist/` and published together:
   - `9Router-<version>-debug.apk` — debug signing, verbose.
   - `9Router-<version>-release.apk` — R8-minified/shrunk, **same debug signing identity** so the variants update each other in place and the in-app updater keeps working with no uninstall.
   Both embedded `versionCode`/`versionName` must match `android/app/build.gradle.kts`; each SHA-256 is recorded in the release notes.
3. The in-app updater prefers the `-release` asset, then `-debug`, then any `.apk` (`UpdateManagerAssetTest`).
4. Do not tag or publish until the route coverage above is reviewed and the release intent is confirmed; no device/emulator runtime claim without device evidence.
5. Release title convention: `9Router Android <version>`, and pass the notes body **without** its leading `# …` heading (the script does this). GitHub already renders the title above the body, so repeating the heading duplicates the name on the release page and shows a raw `#` in the in-app updater.
