import glob
import json
import os

ROOT = '/root/9router'
ASSETS = os.path.join(ROOT, 'android/app/src/main/assets/i18n')
UPSTREAM = os.path.join(ROOT, 'public/i18n/literals')
OVERRIDES = os.path.join(ROOT, 'android/tools/i18n/overrides')

PRODUCTS = (
    'Claude Code', 'Gemini 2.', 'OpenAI Codex', 'Anthropic Claude Code',
    '9Router for Android',
)

removed = 0
for path in sorted(glob.glob(os.path.join(ASSETS, '*.json'))):
    tag = os.path.basename(path)[:-5]
    data = json.load(open(path, encoding='utf-8'))
    upstream = json.load(open(os.path.join(UPSTREAM, f'{tag}.json'), encoding='utf-8')) if os.path.exists(os.path.join(UPSTREAM, f'{tag}.json')) else {}
    curated_path = os.path.join(OVERRIDES, f'{tag}.json')
    curated = json.load(open(curated_path, encoding='utf-8')) if os.path.exists(curated_path) else {}

    keep = {}
    for key, value in data.items():
        if key in upstream or key in curated:
            keep[key] = value
            continue
        # Single words are where context-free MT fails most; English is safer.
        if ' ' not in key and '{' not in key:
            removed += 1
            continue
        if any(product in key for product in PRODUCTS):
            removed += 1
            continue
        keep[key] = value

    json.dump(keep, open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
    open(path, 'a', encoding='utf-8').write('\n')

print('removed', removed)
