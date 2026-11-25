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
import { MODEL_PRICING } from "../open-sse/providers/pricing.js";
import { getModelsByProviderId } from "../open-sse/config/providerModels.js";

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

const lastBlock = (src, key, open = "{", close = "}") => {
  const needle = `${key}: ${open}`;
  const start = src.lastIndexOf(needle);
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
  const oauth = lastBlock(src, "oauth");
  const display = block(src, "display");
  const modelsBlock = block(src, "models", "[", "]");
  const authModes = [...(block(src, "authModes", "[", "]").match(/["']([^"']+)["']/g) || [])]
    .map((v) => v.replace(/["']/g, ""));
  const serviceKinds = [...(block(src, "serviceKinds", "[", "]").match(/["']([^"']+)["']/g) || [])]
    .map((v) => v.replace(/["']/g, ""));
  const category = str(src, /^\s*category:\s*["']([^"']+)["']/m);
  const hasOAuth = /^\s*hasOAuth:\s*true/m.test(src) || category === "oauth" || oauth.length > 0;
  const authType = str(src, /^\s*authType:\s*["']([^"']+)["']/m) || (category === "oauth" ? "oauth" : "apikey");
  // Public installed-app OAuth client shared by the Google CLI providers.
  // Kept here because the upstream constant lives in a module that also imports
  // a CommonJS MITM helper.
  const GOOGLE_CLIENT_ID = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com";
  const GOOGLE_CLIENT_SECRET = "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";
  const googleProvider = ["gemini", "gemini-cli", "antigravity"].includes(id);

  const scopes = [...(oauth.match(/scopes:\s*\[([\s\S]*?)\]/)?.[1] || "")
    .matchAll(/["']([^"']+)["']/g)].map((m) => m[1]);
  const extraBlock = block(oauth, "extraParams");
  const extraParams = {};
  for (const m of extraBlock.matchAll(/([A-Za-z0-9_]+):\s*["']([^"']*)["']/g)) extraParams[m[1]] = m[2];

  const models = [];
  const modelNames = {};
  const modelCapabilities = {};
  if (modelsBlock) {
    for (const m of modelsBlock.matchAll(/\{[\s\S]*?\}/g)) {
      const chunk = m[0];
      const modelId = str(chunk, /^\s*id:\s*["']([^"']+)["']/m);
      if (!modelId) continue;
      models.push(modelId);
      const name = str(chunk, /^\s*name:\s*["']([^"']+)["']/m);
      if (name) modelNames[modelId] = name;
      const caps = [...chunk.matchAll(/capabilities:\s*\[([^\]]*)\]/g)]
        .flatMap((c) => [...c[1].matchAll(/["']([^"']+)["']/g)].map((v) => v[1]));
      const type = str(chunk, /^\s*type:\s*["']([^"']+)["']/m);
      if (type === "image") caps.push("image");
      if (type === "embedding") caps.push("embedding");
      if (caps.length) modelCapabilities[modelId] = [...new Set(caps)];
    }
  }
  // The dashboard reads its model picker from getModelsByProviderId(alias),
  // not from the registry `models` block, so mirror that source too.
  for (const model of getModelsByProviderId(id)) {
    const modelId = model?.id;
    if (!modelId) continue;
    if (!models.includes(modelId)) models.push(modelId);
    if (model.name && !modelNames[modelId]) modelNames[modelId] = model.name;
    const caps = [
      ...(Array.isArray(model.capabilities) ? model.capabilities : []),
      ...(Array.isArray(model.supportedFormats) ? model.supportedFormats : []),
    ].filter((v) => typeof v === "string");
    if (model.type === "image") caps.push("image");
    if (model.type === "embedding") caps.push("embedding");
    if (caps.length) {
      modelCapabilities[modelId] = [...new Set([...(modelCapabilities[modelId] || []), ...caps])];
    }
  }

  entries.push({
    id,
    alias: str(src, /^\s*alias:\s*["']([^"']+)["']/m) || id,
    name: str(display, /^\s*name:\s*["']([^"']+)["']/m) || id,
    category,
    authType,
    authModes,
    hasOAuth,
    priority: Number.parseInt(str(src, /^\s*priority:\s*(\d+)/m) || "999", 10),
    website: str(display, /^\s*website:\s*["']([^"']+)["']/m),
    deprecated: /^\s*deprecated:\s*true/m.test(display),
    hidden: /^\s*hidden:\s*true/m.test(src),
    serviceKinds,
    format: str(transport, /^\s*format:\s*["']([^"']+)["']/m) || "openai",
    baseUrl: str(transport, /^\s*baseUrl:\s*["']([^"']+)["']/m),
    tokenUrl: str(oauth, /^\s*tokenUrl:\s*["']([^"']+)["']/m),
    clientId: str(oauth, /^\s*clientId:\s*["']([^"']+)["']/m) || (googleProvider ? GOOGLE_CLIENT_ID : ""),
    authorizeUrl: str(oauth, /^\s*authorizeUrl:\s*["']([^"']+)["']/m),
    deviceCodeUrl: str(oauth, /^\s*deviceCodeUrl:\s*["']([^"']+)["']/m) ||
      str(oauth, /^\s*deviceUrl:\s*["']([^"']+)["']/m),
    copilotTokenUrl: str(oauth, /^\s*copilotTokenUrl:\s*["']([^"']+)["']/m),
    redirectUri: str(oauth, /^\s*redirectUri:\s*["']([^"']+)["']/m),
    scope: str(oauth, /^\s*scope:\s*["']([^"']+)["']/m) ||
      str(oauth, /^\s*scopes:\s*["']([^"']+)["']/m) ||
      scopes.join(" "),
    clientSecret: str(oauth, /^\s*clientSecret:\s*["']([^"']+)["']/m) || (googleProvider ? GOOGLE_CLIENT_SECRET : ""),
    tokenEncoding: str(oauth, /^\s*encoding:\s*["']([^"']+)["']/m) || "form",
    callbackPath: str(oauth, /^\s*callbackPath:\s*["']([^"']+)["']/m) || "/callback",
    fixedPort: Number.parseInt(str(oauth, /^\s*fixedPort:\s*(\d+)/m) || "0", 10) || 0,
    extraParams,
    models,
    modelNames,
    modelCapabilities,
  });
}

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(entries) + "\n");
const pricingOutput = path.join(root, "android", "app", "src", "main", "assets", "pricing.json");
fs.writeFileSync(pricingOutput, JSON.stringify(MODEL_PRICING) + "\n");
console.log(`Exported ${entries.length} providers → ${path.relative(root, output)}`);
console.log(`Exported ${Object.keys(MODEL_PRICING).length} pricing rows → ${path.relative(root, pricingOutput)}`);
