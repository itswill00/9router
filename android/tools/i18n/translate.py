#!/usr/bin/env python3
"""Build per-locale dictionaries for the Android runtime translator.

Source English strings come from `strings.en.json` (see extract_strings.py).
For every locale we merge, in order of priority:

1. `overrides/<tag>.json`        – hand-curated Android strings.
2. `../../public/i18n/literals/` – the upstream 9Router dictionaries where a
   literal matches exactly (these are professionally curated for short labels).
3. Machine translation           – batched through Google's public endpoint.

Placeholders (`{0}`, `{1}`) and proper nouns are protected with sentinels before
translation and restored afterwards; a template whose placeholders do not
survive is dropped so the runtime falls back to English instead of showing a
broken string.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
LOCALES = [
    "en", "vi", "zh-CN", "zh-TW", "ja", "pt-BR", "pt-PT", "ko", "es", "de",
    "fr", "he", "ar", "ru", "pl", "cs", "nl", "tr", "uk", "tl", "id", "th",
    "km", "hi", "bn", "ur", "ro", "sv", "it", "el", "hu", "fi", "da", "no",
    "fa",
]

ENDPOINT = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl={lang}&"

# Proper nouns and acronyms that must stay verbatim in every language.
PROTECT = [
    "9Router", "OpenAI", "Anthropic", "Claude", "Gemini", "GitHub", "Android",
    "Kotlin", "Jetpack Compose", "Compose", "OkHttp", "Node.js", "Termux",
    "Ollama", "OAuth", "SHA-256", "HTTP/1.1", "HTTP", "HTTPS", "JSON", "TLS",
    "CLI", "SDK", "API", "URL", "MIT", "VS Code", "ChatGPT", "Copilot",
    "Codex", "Cursor", "DALL-E", "Imagen", "FLUX", "Whisper", "Groq",
    "Deepgram", "AssemblyAI", "Firecrawl", "Jina", "Tavily", "Exa", "Brave",
    "Serper", "SearXNG", "Google PSE", "Google", "You.com", "Xquik",
    "ElevenLabs", "Mistral", "MiniMax", "SDWebUI", "Continue.dev", "Kiro",
    "Qoder", "Windsurf", "Trae", "Azure", "Bedrock", "Nebius", "MCP", "RAG",
    "WebSocket", "SSE",
]

PLACEHOLDER = re.compile(r"\{\d+\}")

# Language-specific cleanups applied after machine translation. Keeps widely
# used technical loan words in their English form for Indonesian readers.
POST_REPLACE: dict[str, list[tuple[str, str]]] = {
    "id": [
        ("Gerbang", "Gateway"),
        ("gerbang", "gateway"),
        ("Penyedia", "Provider"),
        ("penyedia", "provider"),
        ("Obrolan", "Chat"),
        ("obrolan", "chat"),
        ("Dasbor", "Dashboard"),
        ("dasbor", "dashboard"),
        ("Perutean", "Routing"),
        ("perutean", "routing"),
        ("Kombo", "Combo"),
        ("kombo", "combo"),
        ("kekakuan sesi", "sticky sesi"),
        ("panggilan balik", "callback"),
    ],
}


def protect(text: str) -> tuple[str, dict[str, str]]:
    sentinels: dict[str, str] = {}

    def keep(token: str) -> str:
        key = f"\u2e28T{len(sentinels)}\u2e29"
        sentinels[key] = token
        return key

    for token in sorted(PROTECT, key=len, reverse=True):
        if token in text:
            text = text.replace(token, keep(token))
    for hole in sorted(set(PLACEHOLDER.findall(text))):
        text = text.replace(hole, keep(hole))
    return text, sentinels


def restore(text: str, sentinels: dict[str, str]) -> str:
    for key, value in sentinels.items():
        text = text.replace(key, value)
    return text


def request_batch(lang: str, items: list[str], attempts: int = 5) -> list[str] | None:
    url = ENDPOINT.format(lang=lang) + "&".join("q=" + urllib.parse.quote(i) for i in items)
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
            if isinstance(payload, list) and len(payload) == len(items):
                return [row if isinstance(row, str) else "".join(str(p) for p in row) for row in payload]
            if isinstance(payload, list) and len(payload) == 1 and isinstance(payload[0], list) and len(items) == 1:
                return ["".join(str(p) for p in payload[0])]
            raise ValueError(f"unexpected payload shape: {payload!r:.120}")
        except Exception as error:  # noqa: BLE001 - retry transient failures
            if attempt == attempts - 1:
                print(f"  ! {lang} batch failed: {error}", file=sys.stderr)
                return None
            time.sleep(1.5 * (attempt + 1))
    return None


def batches(items: list[str], budget: int = 6500) -> list[list[str]]:
    out: list[list[str]] = []
    current: list[str] = []
    size = 0
    for item in items:
        encoded = len(urllib.parse.quote(item)) + 3
        if current and size + encoded > budget:
            out.append(current)
            current, size = [], 0
        current.append(item)
        size += encoded
    if current:
        out.append(current)
    return out


def translate_missing(lang: str, items: list[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for group in batches(items):
        rows = request_batch(lang, group)
        if rows is None:
            # Degrade to one-by-one so a single bad string cannot sink the batch.
            for item in group:
                single = request_batch(lang, [item], attempts=2)
                if single:
                    result[item] = single[0]
        else:
            for source, translated in zip(group, rows):
                result[source] = translated
    return result


def load_json(path: str) -> dict:
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--locales", default=",".join(LOCALES))
    parser.add_argument("--strings", default=os.path.join(os.path.dirname(__file__), "strings.en.json"))
    parser.add_argument("--out", default=os.path.join(ROOT, "android", "app", "src", "main", "assets", "i18n"))
    parser.add_argument("--upstream", default=os.path.join(ROOT, "public", "i18n", "literals"))
    parser.add_argument("--overrides", default=os.path.join(os.path.dirname(__file__), "overrides"))
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--merge", action="store_true")
    args = parser.parse_args()

    with open(args.strings, encoding="utf-8") as handle:
        raw: dict[str, str] = json.load(handle)

    # Key the runtime dictionary by the template form; static strings map to
    # themselves, dynamic ones carry `{n}` holes.
    sources: dict[str, str] = {}
    for _literal, template in raw.items():
        sources[template] = template

    os.makedirs(args.out, exist_ok=True)
    requested = [l.strip() for l in args.locales.split(",") if l.strip()]

    for lang in requested:
        if lang == "en":
            continue
        out_path = os.path.join(args.out, f"{lang}.json")
        if os.path.exists(out_path) and not args.force and not args.merge:
            print(f"= {lang}: exists, skipped")
            continue

        upstream = load_json(os.path.join(args.upstream, f"{lang}.json"))
        overrides = load_json(os.path.join(args.overrides, f"{lang}.json"))

        existing = load_json(out_path) if args.merge else {}
        strings = dict(existing)
        strings.update(upstream)
        strings.update(overrides)

        todo = [s for s in sources if s not in strings and (" " in s or "{" in s)]
        print(f"> {lang}: {len(strings)} curated, {len(todo)} to translate")

        protected: dict[str, tuple[str, dict[str, str]]] = {}
        for item in todo:
            guarded, sentinels = protect(item)
            protected[guarded] = (item, sentinels)

        translated = translate_missing(lang, list(protected.keys()))
        dropped = 0
        for guarded, value in translated.items():
            item, sentinels = protected[guarded]
            text = restore(value, sentinels).strip()
            holes = set(PLACEHOLDER.findall(item))
            if holes != set(PLACEHOLDER.findall(text)):
                dropped += 1
                continue
            if text and text != item:
                strings[item] = text

        # Keep only strings the app can actually ask for.
        final = {k: v for k, v in strings.items() if k in sources or PLACEHOLDER.search(k)}
        rows = []
        for key, value in final.items():
            text = value
            for old, new in POST_REPLACE.get(lang, []):
                text = text.replace(old, new)
            if text and text != key:
                rows.append((key, text))
        final = dict(sorted(rows))
        with open(out_path, "w", encoding="utf-8") as handle:
            json.dump(dict(sorted(final.items())), handle, ensure_ascii=False, indent=0)
            handle.write("\n")
        print(f"  done {lang}: {len(final)} entries ({dropped} dropped)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
