/**
 * Build-time config bridge for the native Android app.
 *
 * The APK must not ship Node/Next, so the provider registry is exported to a
 * plain JSON asset here. Run from the repo root:
 *
 *   node scripts/export-android-config.mjs
 *
 * This is intentionally a light static parse (not an ESM import of the whole
 * registry): a few upstream registry entries reach a CommonJS MITM helper that
 * cannot be named-imported from plain Node ESM. Only declarative fields we
 * actually need on device are extracted.
 */
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const registryDir = path.join(root, "open-sse", "providers", "registry");
const output = path.join(root, "android", "app", "src", "main", "assets", "provider_registry.json");

const str = (src, re, fallback = "") => {
  const m = src.match(re);
  return m ? m[1] : fallback;
};

const block = (src, key, open = "{", close = "}") => {
  const needle = `${key}: ${open}`;
  const start = src.indexOf(needle);
  if (start < 0) return "";
  let depth = 0;
  for (let i = start + needle.length; i < src.length; i++) {
    if (src[i] === open) depth++;
    else if (src[i] === close) {
      if (depth === 0) return src.slice(start, i + 1);
      depth--;
    }
  }
  return "";
};

const entries = [];
for (const file of fs.readdirSync(registryDir).filter((f) => f.endsWith(".js")).sort()) {
  const src = fs.readFileSync(path.join(registryDir, file), "utf8");
  const id = str(src, /^\s*id:\s*["']([^"']+)["']/m);
  if (!id) continue;
  const transport = block(src, "transport");
  const oauth = block(src, "oauth");
  const display = block(src, "display");
  const modelsBlock = block(src, "models", "[", "]");
  const models = modelsBlock
    ? [...modelsBlock.matchAll(/\{\s*id:\s*["']([^"']+)["']/g)].map((m) => m[1])
    : [];

  entries.push({
    id,
    alias: str(src, /^\s*alias:\s*["']([^"']+)["']/m) || id,
    name: str(display, /^\s*name:\s*["']([^"']+)["']/m) || id,
    category: str(src, /^\s*category:\s*["']([^"']+)["']/m),
    authType: str(src, /^\s*authType:\s*["']([^"']+)["']/m) || "apikey",
    format: str(transport, /^\s*format:\s*["']([^"']+)["']/m) || "openai",
    baseUrl: str(transport, /^\s*baseUrl:\s*["']([^"']+)["']/m),
    tokenUrl: str(oauth, /^\s*tokenUrl:\s*["']([^"']+)["']/m),
    clientId: str(oauth, /^\s*clientId:\s*["']([^"']+)["']/m),
    authorizeUrl: str(oauth, /^\s*authorizeUrl:\s*["']([^"']+)["']/m),
    deviceCodeUrl: str(oauth, /^\s*deviceCodeUrl:\s*["']([^"']+)["']/m),
    redirectUri: str(oauth, /^\s*redirectUri:\s*["']([^"']+)["']/m),
    scope: str(oauth, /^\s*scope:\s*["']([^"']+)["']/m),
    models,
  });
}

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(entries) + "\n");
console.log(`Exported ${entries.length} providers → ${path.relative(root, output)}`);
