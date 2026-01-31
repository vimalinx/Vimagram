import { spawn } from 'node:child_process';
import { writeFileSync, unlinkSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

const PORT = 38788;
const BASE_URL = `http://127.0.0.1:${PORT}`;
const USERS_FILE = resolve('temp-test-users.json');

// Cleanup previous run
if (existsSync(USERS_FILE)) unlinkSync(USERS_FILE);

// Initialize empty users file
writeFileSync(USERS_FILE, JSON.stringify({ users: [] }));

console.log('--- Starting Vimalinx Server Verification ---');

// 1. Start Server
const serverEnv = {
  ...process.env,
  TEST_SERVER_PORT: String(PORT),
  TEST_USERS_FILE: USERS_FILE,
  TEST_ALLOW_REGISTRATION: 'true',
  TEST_INBOUND_MODE: 'poll'
};

const serverProcess = spawn('node', ['server/server.mjs'], { 
  env: serverEnv,
  stdio: 'pipe' 
});

let serverReady = false;

serverProcess.stdout.on('data', (data) => {
  const msg = data.toString();
  // console.log(`[SERVER] ${msg.trim()}`);
  if (msg.includes('listening on')) serverReady = true;
});

serverProcess.stderr.on('data', (data) => {
  console.error(`[SERVER ERR] ${data.toString()}`);
});

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function waitForServer() {
  for (let i = 0; i < 20; i++) {
    if (serverReady) return;
    try {
      const res = await fetch(`${BASE_URL}/healthz`);
      if (res.ok) return;
    } catch (e) {}
    await sleep(200);
  }
  throw new Error('Server failed to start');
}

async function runTests() {
  try {
    await waitForServer();
    console.log('✅ Server started');

    // Test 1: Register
    console.log('\nTest 1: Registration');
    const regRes = await fetch(`${BASE_URL}/api/register`, {
      method: 'POST',
      body: JSON.stringify({ userId: 'testuser', password: 'password123', displayName: 'Test User' })
    });
    const regData = await regRes.json();
    if (!regData.ok) throw new Error(`Registration failed: ${JSON.stringify(regData)}`);
    console.log('✅ Registered user: testuser');

    // Test 2: Login
    console.log('\nTest 2: Login (Account)');
    const loginRes = await fetch(`${BASE_URL}/api/account/login`, {
      method: 'POST',
      body: JSON.stringify({ userId: 'testuser', password: 'password123' })
    });
    const loginData = await loginRes.json();
    if (!loginData.ok) throw new Error(`Login failed: ${JSON.stringify(loginData)}`);
    console.log('✅ Logged in');

    // Test 3: Generate Host Token
    console.log('\nTest 3: Generate Host Token');
    const tokenRes = await fetch(`${BASE_URL}/api/token`, {
      method: 'POST',
      body: JSON.stringify({ userId: 'testuser', password: 'password123' })
    });
    const tokenData = await tokenRes.json();
    if (!tokenData.ok) throw new Error(`Token gen failed: ${JSON.stringify(tokenData)}`);
    const HOST_TOKEN = tokenData.token;
    console.log('✅ Generated Token:', HOST_TOKEN.substring(0, 8) + '...');

    // Test 4: Send Inbound Message (App -> Server -> Gateway[Simulated])
    console.log('\nTest 4: Inbound Message (Client -> Server)');
    const msgRes = await fetch(`${BASE_URL}/api/message`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: 'testuser', token: HOST_TOKEN, text: 'Hello from Client' })
    });
    const msgData = await msgRes.json();
    if (!msgData.ok) throw new Error(`Send message failed: ${JSON.stringify(msgData)}`);
    console.log('✅ Message queued');

    // Test 5: Poll Message (Gateway -> Server)
    console.log('\nTest 5: Poll Message (Gateway <- Server)');
    const pollRes = await fetch(`${BASE_URL}/api/poll?waitMs=1000`, {
      headers: { 'Authorization': `Bearer ${HOST_TOKEN}`, 'x-vimalinx-user': 'testuser' }
    });
    const pollData = await pollRes.json();
    if (!pollData.ok || pollData.messages.length === 0) throw new Error('Poll failed or empty');
    if (pollData.messages[0].text !== 'Hello from Client') throw new Error('Message content mismatch');
    console.log('✅ Message retrieved via Poll');

    // Test 6: Outbound (Gateway -> Server -> Client)
    console.log('\nTest 6: Outbound Message (Gateway -> Server)');
    const chatId = pollData.messages[0].chatId;
    const sendRes = await fetch(`${BASE_URL}/send`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${HOST_TOKEN}` },
      body: JSON.stringify({ chatId, text: 'Reply from Gateway' })
    });
    const sendData = await sendRes.json();
    if (!sendData.ok) throw new Error(`Outbound failed: ${JSON.stringify(sendData)}`);
    console.log('✅ Outbound delivered');

    console.log('\n--- ALL TESTS PASSED ---');
  } catch (err) {
    console.error('\n❌ TEST FAILED:', err.message);
    process.exit(1);
  } finally {
    serverProcess.kill();
    if (existsSync(USERS_FILE)) unlinkSync(USERS_FILE);
  }
}

runTests();
