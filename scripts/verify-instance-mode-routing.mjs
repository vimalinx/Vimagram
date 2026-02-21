import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const ROOT = resolve(__dirname, '..');

const PORT = 38880 + Math.floor(Math.random() * 500);
const BASE_URL = `http://127.0.0.1:${PORT}`;
const USER_ID = 'verify-user';
const TOKEN = 'verify-token';
const CHAT_ID = 'machine:testhost/session-verify';

const tempDir = mkdtempSync(join(tmpdir(), 'vimalinx-instance-verify-'));
const instancesFile = join(tempDir, 'instances.json');

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function sleep(ms) {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, ms));
}

async function waitForServer(maxAttempts = 30) {
  for (let i = 0; i < maxAttempts; i += 1) {
    try {
      const res = await fetch(`${BASE_URL}/healthz`);
      if (res.ok) return;
    } catch {
      // retry until timeout
    }
    await sleep(200);
  }
  throw new Error('server did not become ready in time');
}

async function requestJson(url, options = {}) {
  const res = await fetch(url, options);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}: ${JSON.stringify(data)}`);
  }
  return data;
}

async function waitForFile(filePath, maxAttempts = 20) {
  for (let i = 0; i < maxAttempts; i += 1) {
    if (existsSync(filePath)) return;
    await sleep(100);
  }
  throw new Error(`file not created in time: ${filePath}`);
}

async function main() {
  console.log('--- Verify instance mode routing ---');

  const usersInline = JSON.stringify({
    users: [{ id: USER_ID, token: TOKEN, displayName: 'Verify User' }],
  });

  const server = spawn('node', ['server/server.mjs'], {
    cwd: ROOT,
    env: {
      ...process.env,
      TEST_SERVER_PORT: String(PORT),
      TEST_BIND_HOST: '127.0.0.1',
      TEST_USERS: usersInline,
      TEST_ALLOW_REGISTRATION: 'false',
      TEST_INSTANCES_FILE: instancesFile,
    },
    stdio: 'pipe',
  });

  let stderr = '';
  server.stderr.on('data', (chunk) => {
    stderr += chunk.toString();
  });

  try {
    await waitForServer();
    console.log('✅ server started');

    await requestJson(`${BASE_URL}/api/message`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: USER_ID,
        token: TOKEN,
        chatId: CHAT_ID,
        text: '/new',
        instanceModelTierId: 'glm-4.7',
        instanceIdentityId: 'docs',
      }),
    });
    console.log('✅ posted /api/message with instance config');

    const poll = await requestJson(
      `${BASE_URL}/api/poll?userId=${encodeURIComponent(USER_ID)}&token=${encodeURIComponent(TOKEN)}&waitMs=20`,
    );
    const first = Array.isArray(poll.messages) ? poll.messages[0] : null;
    assert(first, 'poll returned no messages');

    assert(first.modeId === 'inst_glm_4_7_docs', `unexpected modeId: ${String(first.modeId)}`);
    assert(/^[a-z0-9_-]{1,32}$/.test(first.modeId), `modeId is not safe format: ${first.modeId}`);
    assert(first.modeLabel === 'Pro · Writing', `unexpected modeLabel: ${String(first.modeLabel)}`);
    assert(first.modelHint === 'zai/glm-4.7', `unexpected modelHint: ${String(first.modelHint)}`);
    assert(first.agentHint === 'docs', `unexpected agentHint: ${String(first.agentHint)}`);
    console.log('✅ poll contains expected instance-derived mode metadata');

    await waitForFile(instancesFile);
    const stored = JSON.parse(readFileSync(instancesFile, 'utf8'));
    const entry = Array.isArray(stored.instances)
      ? stored.instances.find((item) => item.userId === USER_ID && item.chatId === CHAT_ID)
      : null;
    assert(entry, 'instance config entry missing from instances file');
    assert(entry.modelTierId === 'glm-4.7', 'stored modelTierId mismatch');
    assert(entry.identityId === 'docs', 'stored identityId mismatch');
    console.log('✅ instance config persisted to instances file');

    console.log('--- verify-instance-mode-routing: OK ---');
  } finally {
    server.kill();
    if (existsSync(tempDir)) {
      rmSync(tempDir, { recursive: true, force: true });
    }
    if (stderr.trim()) {
      // Keep stderr output visible when server emitted warnings/errors.
      console.log(`server stderr:\n${stderr.trim()}`);
    }
  }
}

main().catch((err) => {
  console.error(`❌ ${err.message}`);
  process.exit(1);
});
