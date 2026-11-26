#!/usr/bin/env python3
"""Extract translatable UI strings from the Android sources.

The runtime translator keys off the English literal that reaches `Text(...)`,
so the dictionary is built from the literals in the UI (plus the few app-level
surfaces that render text directly: toasts, notifications and updater errors).

Interpolations (`$name` and `${expr}`) become `{0}`, `{1}`, ... placeholders so
the runtime can resolve dynamic text through a template fallback.
"""

from __future__ import annotations

import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SRC = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "ninerouter", "android")

SOURCE_GLOBS = [
    os.path.join(SRC, "ui", "*.kt"),
    os.path.join(SRC, "MainActivity.kt"),
    os.path.join(SRC, "GatewayService.kt"),
    os.path.join(SRC, "update", "UpdateManager.kt"),
]

# Literals that must never be machine-translated: sample data, formats,
# commands, identifiers and protocol names that stay verbatim.
SKIP_EXACT = {
    "very-long-account-address@example-company-domain.com",
    "yyyy-MM-dd HH:mm:ss",
    r"(\d+)\.(\d+)\.(\d+)",
    "9Router",
    "$SKILLS_BLOB/$id/SKILL.md",
    "$SKILLS_RAW/$id/SKILL.md",
    "providers/$id.png",
}

BRAND_ONLY = {
    "OpenAI", "Anthropic", "Claude", "Gemini", "GitHub", "Android", "Kotlin",
    "Compose", "OkHttp", "Node.js", "Termux", "Ollama", "SDK", "API", "URL",
    "HTTP", "HTTPS", "JSON", "TLS", "CLI", "OAuth", "MIT", "VS Code",
    "ChatGPT", "Copilot", "Codex", "Cursor", "DALL-E", "Imagen", "FLUX",
    "Whisper", "Groq", "Deepgram", "AssemblyAI", "Firecrawl", "Jina", "Tavily",
    "Exa", "Brave", "Serper", "SearXNG", "Google", "PSE", "You.com", "Xquik",
    "ElevenLabs", "Edge", "Mistral", "MiniMax", "SDWebUI", "Continue.dev",
    "Kiro", "Qoder", "Windsurf", "Trae", "Azure", "Bedrock", "Nebius",
}


def scan_literals(src: str):
    """Yield (value, position) for every string literal, skipping comments."""
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n:
            if src[i + 1] == "/":
                j = src.find("\n", i)
                i = n if j < 0 else j
                continue
            if src[i + 1] == "*":
                j = src.find("*/", i + 2)
                i = n if j < 0 else j + 2
                continue
        if c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                yield src[i + 3:j], i, True
                i = j + 3
                continue
            j, buf = i + 1, []
            while j < n:
                if src[j] == "\\":
                    buf.append(src[j:j + 2])
                    j += 2
                    continue
                if src[j] == '"':
                    break
                buf.append(src[j])
                j += 1
            yield "".join(buf), i, False
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            i = j + 1
            continue
        i += 1


def unescape(raw: str) -> str:
    return (
        raw.replace('\\"', '"')
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\\", "\\")
        .replace("\\$", "$")
    )


INTERP = re.compile(r"\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*")


def to_template(value: str) -> str:
    counter = {"n": 0}

    def repl(_m):
        idx = counter["n"]
        counter["n"] += 1
        return "{%d}" % idx

    return INTERP.sub(repl, value)


def looks_like_code(value: str) -> bool:
    if "\n" in value or "\t" in value:
        return True
    if len(value) > 600:
        return True
    if re.match(r"^(https?://|git@|Bearer |sk-|sk_|ghp_)", value):
        return True
    if re.search(r"[\w.+-]+@[\w-]+\.[\w.-]+", value):
        return True
    if re.search(r"^(\.{0,2}/|~/)", value) or value.startswith("/v1"):
        return True
    if re.search(r"[<>{}]", value):
        return True
    if re.search(r"\\u[0-9a-fA-F]{4}", value):
        return True
    if re.match(r"^[A-Za-z0-9_.:/@#-]+$", value) and not re.match(r"^[A-Z][a-z]{2,}$", value):
        return True
    if value.startswith("[") or value.startswith("^"):
        return True
    if re.match(r"^[A-Za-z-]+/[A-Za-z0-9.*+-]+$", value):
        return True
    if re.match(r"^(MMM|EEE|HH|mm|ss|yyyy|dd)\b", value):
        return True
    if value.strip() == "else":
        return True
    # Shell / config / code fragments.
    if re.search(r"\b(export|curl|npm|npx|adb|gradlew|fun |val |var |class |import |package |println)\b", value):
        return True
    if "::" in value or "--" in value:
        return True
    return False


def is_translatable(value: str) -> bool:
    if not any(ch.isalpha() for ch in value):
        return False
    if value.strip() == "":
        return False
    if value in SKIP_EXACT or value in BRAND_ONLY:
        return False
    if looks_like_code(value):
        return False
    # Needs an alphabetic anchor of at least three letters.
    if len(re.sub(r"[^A-Za-z]", "", value)) < 3:
        return False
    return True


def main() -> int:
    import glob as _glob

    files: list[str] = []
    for pattern in SOURCE_GLOBS:
        files.extend(sorted(_glob.glob(pattern)))
    # The i18n infrastructure contains locale names and asset paths; they are
    # not UI copy.
    files = [path for path in files if not path.endswith("I18n.kt")]

    strings: dict[str, str] = {}
    for path in files:
        with open(path, encoding="utf-8") as handle:
            src = handle.read()
        for raw, _pos, is_raw in scan_literals(src):
            if is_raw:
                continue
            value = unescape(raw)
            if not is_translatable(value):
                continue
            template = to_template(value)
            if "{" in template:
                # A template needs a real phrase around its holes, otherwise it
                # would match unrelated dynamic strings.
                literal = re.sub(r"\{\d+\}", " ", template)
                if " " not in literal.strip() or len(re.sub(r"[^A-Za-z]", "", literal)) < 4:
                    continue
            strings[value] = template

    ordered = {key: strings[key] for key in sorted(strings, key=lambda k: (k.lower(), k))}
    out = os.path.join(os.path.dirname(__file__), "strings.en.json")
    with open(out, "w", encoding="utf-8") as handle:
        json.dump(ordered, handle, ensure_ascii=False, indent=1, sort_keys=False)
        handle.write("\n")
    print(f"{len(ordered)} strings -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
