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

// Public installed-app OAuth clients. The upstream constants live in a module
// that also imports a CommonJS MITM helper, so keep the two Google clients here.
const GOOGLE_CLIENT_ID = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com";
const GOOGLE_CLIENT_SECRET = "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";
const ANTIGRAVITY_CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
const ANTIGRAVITY_CLIENT_SECRET = "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";

// A few OAuth configs live in src/lib/oauth but not in the registry's oauth
// block (or reference module constants the static parser cannot resolve).
const OAUTH_OVERRIDES = {
  nous: {
    clientId: "hermes-cli",
    deviceCodeUrl: "https://portal.nousresearch.com/api/oauth/device/code",
    tokenUrl: "https://portal.nousresearch.com/api/oauth/token",
    agentKeyUrl: "https://portal.nousresearch.com/api/oauth/agent-key",
    scope: "inference:invoke inference:mint_agent_key",
    baseUrl: "https://inference-api.nousresearch.com/v1/chat/completions",
  },
  xai: {
    clientId: "b1a00492-073a-47ea-816f-4c329264a828",
    authorizeUrl: "https://auth.x.ai/oauth2/authorize",
    tokenUrl: "https://auth.x.ai/oauth2/token",
    scope: "openid profile email offline_access grok-cli:access api:access",
    codeChallengeMethod: "S256",
    fixedPort: 56121,
    callbackPath: "/callback",
    redirectUri: "http://127.0.0.1:56121/callback",
    extraParams: { plan: "generic", referrer: "cli-proxy-api" },
  },
};

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

/** Like block(), but tolerates a missing space before the opening brace. */
const looseBlock = (src, key, open = "{", close = "}") => {
  const m = src.match(new RegExp(`${key}:\\s*\\${open}`));
  if (!m) return "";
  const start = m.index;
  let depth = 0;
  for (let i = start + m[0].length; i < src.length; i++) {
    if (src[i] === open) depth++;
    else if (src[i] === close) {
      if (depth === 0) return src.slice(start, i + 1);
      depth--;
    }
  }
  return "";
};

/**
 * Extract the declarative media block (imageConfig, ttsConfig, ...) that the
 * native gateway needs to reach a provider without a Node runtime.
 *
 * Only literal values are exported; anything resolved through a module
 * constant (for example SearXNG's self-hosted URL) is left blank so the app
 * can report the provider as unsupported instead of dialing the wrong host.
 */
const mediaConfig = (src, key) => {
  const raw = block(src, key);
  if (!raw) return null;
  const baseUrl = str(raw, /baseUrl:\s*["'`]([^"'`$]+)["'`]/);
  const headers = {};
  for (const m of looseBlock(raw, "headers").matchAll(/["']?([A-Za-z0-9_-]+)["']?\s*:\s*["']([^"']*)["']/g)) {
    headers[m[1]] = m[2];
  }
  const list = (name) => [...(block(raw, name, "[", "]").match(/["']([^"']+)["']/g) || [])]
    .map((v) => v.replace(/["']/g, ""));
  const num = (name, fallback = 0) =>
    Number.parseInt(str(raw, new RegExp(`${name}:\\s*(\\d+)`)) || String(fallback), 10);
  return {
    baseUrl,
    method: str(raw, /method:\s*["']([^"']+)["']/).toUpperCase(),
    authType: str(raw, /authType:\s*["']([^"']+)["']/),
    authHeader: str(raw, /authHeader:\s*["']([^"']+)["']/),
    format: str(raw, /format:\s*["']([^"']+)["']/),
    defaultModel: str(raw, /defaultModel:\s*["']([^"']+)["']/),
    headers,
    bodyFields: list("bodyFields"),
    formats: list("formats"),
    defaultMaxResults: num("defaultMaxResults", 5),
    maxMaxResults: num("maxMaxResults", 100),
    timeoutMs: num("timeoutMs", 15000),
  };
};

const entries = [];
for (const file of fs.readdirSync(registryDir).filter((f) => f.endsWith(".js")).sort()) {
  const src = fs.readFileSync(path.join(registryDir, file), "utf8");
  const id = str(src, /^\s*id:\s*["']([^"']+)["']/m);
  if (!id) continue;
  const transport = block(src, "transport");
  const baseUrlsBlock = block(src, "baseUrls", "[", "]");
  const authBlock = block(src, "auth") || block(transport, "auth");
  const authHeader = str(authBlock, /header:\s*["']([^"']+)["']/) ||
    str(transport, /^\s*authHeader:\s*["']([^"']+)["']/m);
  const authScheme = str(authBlock, /scheme:\s*["']([^"']+)["']/) || "bearer";
  const urlSuffix = str(transport, /^\s*urlSuffix:\s*["']([^"']+)["']/m);
  const transportHeaders = {};
  for (const m of block(transport, "headers").matchAll(/["']?([A-Za-z0-9_-]+)["']?\s*:\s*["']([^"']*)["']/g)) {
    transportHeaders[m[1]] = m[2];
  }
  // Some transports build baseUrl from a module constant / template literal.
  const TEMPLATE_CONSTS = {
    GROK_CLI_BASE_URL: "https://cli-chat-proxy.grok.com/v1",
    ANTIGRAVITY_IDE_BASE_URL: "https://daily-cloudcode-pa.googleapis.com",
  };
  const rawBaseUrl = str(transport, /^\s*baseUrl:\s*[`"']([^`"']+)[`"']/m);
  const baseUrl = rawBaseUrl
    ? rawBaseUrl.replace(/\$\{([A-Z0-9_]+)\}/g, (_, key) => TEMPLATE_CONSTS[key] ?? "")
    : (id === "antigravity" ? TEMPLATE_CONSTS.ANTIGRAVITY_IDE_BASE_URL : "");
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
  const googleClient = id === "antigravity"
    ? { id: ANTIGRAVITY_CLIENT_ID, secret: ANTIGRAVITY_CLIENT_SECRET }
    : ["gemini", "gemini-cli"].includes(id)
      ? { id: GOOGLE_CLIENT_ID, secret: GOOGLE_CLIENT_SECRET }
      : { id: "", secret: "" };

  const scopes = [...(oauth.match(/scopes:\s*\[([\s\S]*?)\]/)?.[1] || "")
    .matchAll(/["']([^"']+)["']/g)].map((m) => m[1]);
  const extraBlock = block(oauth, "extraParams");
  const extraParams = {};
  for (const m of extraBlock.matchAll(/([A-Za-z0-9_]+):\s*["']([^"']*)["']/g)) extraParams[m[1]] = m[2];
  for (const key of ["audience", "referrer", "platform", "providerId"]) {
    const value = str(oauth, new RegExp(`^\\s*${key}:\\s*["']([^"']+)["']`, "m"));
    if (value) extraParams[key] = value;
  }
  const refreshBlock = block(oauth, "refresh");

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

  const entry = {
    id,
    alias: str(src, /^\s*alias:\s*["']([^"']+)["']/m) || id,
    name: str(display, /^\s*name:\s*["']([^"']+)["']/m) || id,
    color: str(display, /^\s*color:\s*["']([^"']+)["']/m),
    textIcon: str(display, /^\s*textIcon:\s*["']([^"']+)["']/m),
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
    baseUrl,
    transportHeaders,
    authHeader,
    authScheme,
    urlSuffix,
    tokenUrl: str(oauth, /^\s*tokenUrl:\s*["']([^"']+)["']/m) ||
      str(transport, /^\s*tokenUrl:\s*["']([^"']+)["']/m),
    clientId: str(oauth, /^\s*clientId:\s*["']([^"']+)["']/m) ||
      str(transport, /^\s*clientId:\s*["']([^"']+)["']/m) ||
      googleClient.id,
    authorizeUrl: str(oauth, /^\s*authorizeUrl:\s*["']([^"']+)["']/m),
    deviceCodeUrl: str(oauth, /^\s*deviceCodeUrl:\s*["']([^"']+)["']/m) ||
      str(oauth, /^\s*deviceUrl:\s*["']([^"']+)["']/m),
    deviceAuthUrl: str(oauth, /^\s*authorizeDeviceUrl:\s*["']([^"']+)["']/m) ||
      str(oauth, /^\s*deviceAuthUrl:\s*["']([^"']+)["']/m),
    stateUrl: str(oauth, /^\s*stateUrl:\s*["']([^"']+)["']/m),
    initiateUrl: str(oauth, /^\s*initiateUrl:\s*["']([^"']+)["']/m),
    pollUrlBase: str(oauth, /^\s*pollUrlBase:\s*["']([^"']+)["']/m),
    platform: str(oauth, /^\s*platform:\s*["']([^"']+)["']/m),
    userAgent: str(oauth, /^\s*userAgent:\s*["']([^"']+)["']/m),
    pollInterval: Number.parseInt(str(oauth, /^\s*pollInterval:\s*(\d+)/m) || "5", 10),
    copilotTokenUrl: str(oauth, /^\s*copilotTokenUrl:\s*["']([^"']+)["']/m),
    userInfoUrl: str(oauth, /^\s*userInfoUrl:\s*["']([^"']+)["']/m),
    agentKeyUrl: str(oauth, /^\s*agentKeyUrl:\s*["']([^"']+)["']/m),
    redirectUri: str(oauth, /^\s*redirectUri:\s*["']([^"']+)["']/m),
    scope: str(oauth, /^\s*scope:\s*["']([^"']+)["']/m) ||
      str(oauth, /^\s*scopes:\s*["']([^"']+)["']/m) ||
      scopes.join(" "),
    clientSecret: str(oauth, /^\s*clientSecret:\s*["']([^"']+)["']/m) ||
      str(transport, /^\s*clientSecret:\s*["']([^"']+)["']/m) ||
      googleClient.secret,
    tokenEncoding: str(oauth, /^\s*encoding:\s*["']([^"']+)["']/m) || "form",
    codeChallengeMethod: str(oauth, /^\s*codeChallengeMethod:\s*["']([^"']+)["']/m),
    refreshUrl: str(oauth, /^\s*refreshUrl:\s*["']([^"']+)["']/m) ||
      str(transport, /^\s*refreshUrl:\s*["']([^"']+)["']/m),
    refreshEncoding: str(refreshBlock, /^\s*encoding:\s*["']([^"']+)["']/m),
    refreshScope: str(refreshBlock, /^\s*scope:\s*["']([^"']+)["']/m),
    callbackPath: str(oauth, /^\s*callbackPath:\s*["']([^"']+)["']/m) || "/callback",
    fixedPort: Number.parseInt(str(oauth, /^\s*fixedPort:\s*(\d+)/m) || "0", 10) || 0,
    extraParams,
    models,
    modelNames,
    modelCapabilities,
    noAuth: /^\s*noAuth:\s*true/m.test(src),
    imageConfig: mediaConfig(src, "imageConfig"),
    ttsConfig: mediaConfig(src, "ttsConfig"),
    sttConfig: mediaConfig(src, "sttConfig"),
    searchConfig: mediaConfig(src, "searchConfig"),
    fetchConfig: mediaConfig(src, "fetchConfig"),
  };
  const override = OAUTH_OVERRIDES[id];
  if (override) {
    const { extraParams: extraOverride, ...rest } = override;
    Object.assign(entry, rest);
    if (extraOverride) entry.extraParams = { ...entry.extraParams, ...extraOverride };
  }
  entries.push(entry);
}

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(entries) + "\n");
const pricingOutput = path.join(root, "android", "app", "src", "main", "assets", "pricing.json");
fs.writeFileSync(pricingOutput, JSON.stringify(MODEL_PRICING) + "\n");
console.log(`Exported ${entries.length} providers → ${path.relative(root, output)}`);
console.log(`Exported ${Object.keys(MODEL_PRICING).length} pricing rows → ${path.relative(root, pricingOutput)}`);
