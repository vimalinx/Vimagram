#!/usr/bin/env node
import { randomBytes } from "node:crypto";
import { existsSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { createInterface } from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";

const argv = process.argv.slice(2);
let outputPath = ".env";
let useDefaults = false;
for (let i = 0; i < argv.length; i += 1) {
  const arg = argv[i];
  if (arg === "--output" && argv[i + 1]) {
    outputPath = argv[i + 1];
    i += 1;
    continue;
  }
  if (arg === "--defaults") {
    useDefaults = true;
  }
}

const targetPath = resolve(process.cwd(), outputPath);
const rl = createInterface({ input, output });

function formatEnvValue(value) {
  if (value === "") return "";
  if (/[^A-Za-z0-9_./:-]/.test(value)) {
    const escaped = value.replace(/\\/g, "\\\\").replace(/"/g, "\\\"");
    return `"${escaped}"`;
  }
  return value;
}

function generateSecret(bytes = 24) {
  return randomBytes(bytes).toString("base64url");
}

async function askString(label, defaultValue = "", { optional = false } = {}) {
  const suffix = defaultValue ? ` [${defaultValue}]` : optional ? " [optional]" : "";
  const answer = (await rl.question(`${label}${suffix}: `)).trim();
  if (!answer) return defaultValue;
  return answer;
}

async function askBool(label, defaultValue) {
  const suffix = defaultValue ? "[Y/n]" : "[y/N]";
  while (true) {
    const answer = (await rl.question(`${label} ${suffix}: `)).trim().toLowerCase();
    if (!answer) return defaultValue;
    if (["y", "yes", "true", "1"].includes(answer)) return true;
    if (["n", "no", "false", "0"].includes(answer)) return false;
  }
}

async function askNumber(label, defaultValue) {
  while (true) {
    const answer = (await rl.question(`${label} [${defaultValue}]: `)).trim();
    if (!answer) return defaultValue;
    const parsed = Number.parseInt(answer, 10);
    if (Number.isFinite(parsed) && parsed > 0) return parsed;
  }
}

async function askSecret(label, defaultValue = "") {
  const value = (await rl.question(`${label} [optional]: `)).trim();
  if (value) return value;
  const shouldGenerate = await askBool(`Generate ${label}?`, false);
  if (shouldGenerate) return generateSecret();
  return defaultValue;
}

function buildDefaults() {
  const hmacSecret = generateSecret();
  const serverToken = generateSecret();
  const secretKey = generateSecret(32);
  return {
    serverPort: 8788,
    bindHost: "0.0.0.0",
    usersFile: "./users.json",
    usersWriteFile: "",
    allowRegistration: true,
    inviteCodes: "",
    inboundMode: "poll",
    gatewayUrl: "",
    gatewayToken: "",
    serverToken,
    hmacSecret,
    requireSignature: true,
    signatureTtlMs: 300000,
    secretKey,
    defaultUserId: "",
    defaultUserToken: "",
    trustProxy: false,
    rateLimit: true,
  };
}

async function main() {
  const defaults = buildDefaults();
  if (existsSync(targetPath)) {
    const overwrite = await askBool(`Overwrite ${targetPath}?`, false);
    if (!overwrite) {
      rl.close();
      return;
    }
  }

  const serverPort = useDefaults
    ? defaults.serverPort
    : await askNumber("TEST_SERVER_PORT", defaults.serverPort);
  const bindHost = useDefaults
    ? defaults.bindHost
    : await askString("TEST_BIND_HOST", defaults.bindHost);
  const usersFile = useDefaults
    ? defaults.usersFile
    : await askString("TEST_USERS_FILE", defaults.usersFile);
  const usersWriteFile = useDefaults
    ? defaults.usersWriteFile
    : await askString("TEST_USERS_WRITE_FILE", defaults.usersWriteFile, { optional: true });
  const allowRegistration = useDefaults
    ? defaults.allowRegistration
    : await askBool("TEST_ALLOW_REGISTRATION", defaults.allowRegistration);
  const inviteCodes = useDefaults
    ? defaults.inviteCodes
    : await askString("TEST_INVITE_CODES", defaults.inviteCodes, { optional: true });
  const inboundMode = useDefaults
    ? defaults.inboundMode
    : await askString("TEST_INBOUND_MODE (poll/webhook)", defaults.inboundMode);
  const gatewayUrl = useDefaults
    ? defaults.gatewayUrl
    : await askString("TEST_GATEWAY_URL", defaults.gatewayUrl, { optional: true });
  const gatewayToken = useDefaults
    ? defaults.gatewayToken
    : await askString("TEST_GATEWAY_TOKEN", defaults.gatewayToken, { optional: true });
  const serverToken = useDefaults ? defaults.serverToken : await askSecret("TEST_SERVER_TOKEN");
  const hmacSecret = useDefaults ? defaults.hmacSecret : await askSecret("TEST_HMAC_SECRET");
  const requireSignatureDefault = hmacSecret ? true : false;
  const requireSignature = useDefaults
    ? defaults.requireSignature
    : await askBool("TEST_REQUIRE_SIGNATURE", requireSignatureDefault);
  const signatureTtlMs = useDefaults
    ? defaults.signatureTtlMs
    : await askNumber("TEST_SIGNATURE_TTL_MS", defaults.signatureTtlMs);
  const secretKey = useDefaults
    ? defaults.secretKey
    : await askSecret("TEST_SECRET_KEY (token hashing)");
  const defaultUserId = useDefaults
    ? defaults.defaultUserId
    : await askString("TEST_DEFAULT_USER_ID", defaults.defaultUserId, { optional: true });
  const defaultUserToken = useDefaults
    ? defaults.defaultUserToken
    : await askString("TEST_DEFAULT_USER_TOKEN", defaults.defaultUserToken, { optional: true });
  const trustProxy = useDefaults
    ? defaults.trustProxy
    : await askBool("TEST_TRUST_PROXY", defaults.trustProxy);
  const rateLimit = useDefaults
    ? defaults.rateLimit
    : await askBool("TEST_RATE_LIMIT", defaults.rateLimit);

  const entries = [
    ["TEST_SERVER_PORT", String(serverPort)],
    ["TEST_BIND_HOST", bindHost],
    ["TEST_USERS_FILE", usersFile],
    ["TEST_USERS_WRITE_FILE", usersWriteFile],
    ["TEST_ALLOW_REGISTRATION", String(allowRegistration)],
    ["TEST_INVITE_CODES", inviteCodes],
    ["TEST_INBOUND_MODE", inboundMode],
    ["TEST_GATEWAY_URL", gatewayUrl],
    ["TEST_GATEWAY_TOKEN", gatewayToken],
    ["TEST_SERVER_TOKEN", serverToken],
    ["TEST_HMAC_SECRET", hmacSecret],
    ["TEST_REQUIRE_SIGNATURE", String(requireSignature)],
    ["TEST_SIGNATURE_TTL_MS", String(signatureTtlMs)],
    ["TEST_SECRET_KEY", secretKey],
    ["TEST_DEFAULT_USER_ID", defaultUserId],
    ["TEST_DEFAULT_USER_TOKEN", defaultUserToken],
    ["TEST_TRUST_PROXY", String(trustProxy)],
    ["TEST_RATE_LIMIT", String(rateLimit)],
  ];

  const lines = ["# Generated by server/setup.mjs", ""];
  for (const [key, value] of entries) {
    if (!value) continue;
    lines.push(`${key}=${formatEnvValue(value)}`);
  }

  writeFileSync(targetPath, `${lines.join("\n")}\n`, "utf-8");
  rl.close();

  console.log(`\nSaved ${targetPath}`);
  console.log("\nTo use:\n");
  console.log("  set -a && source .env && set +a");
  console.log("  node server/server.mjs\n");
}

main().catch((err) => {
  console.error(err);
  rl.close();
  process.exit(1);
});
