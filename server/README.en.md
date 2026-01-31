# Vimalinx Server

English | [中文](README.md)

Minimal chat server for the Vimalinx Server channel plugin (`vimalinx`).

## Quick start (local, poll mode)

```bash
export TEST_SERVER_PORT=8788
export TEST_USERS_FILE=/path/to/vimalinx-users.json
export TEST_ALLOW_REGISTRATION=true

node server/server.mjs
```

- `TEST_USERS_FILE` is required to persist registrations.
- Poll mode is default; the plugin will poll `/api/poll`.

## Config

Set environment variables:

- `TEST_SERVER_PORT` (default `8788`)
- `TEST_BIND_HOST` (bind address, default `0.0.0.0`)
- `TEST_SERVER_TOKEN` (optional global secret for `/send`; user token also works)
- `TEST_GATEWAY_URL` (fallback Gateway webhook URL)
- `TEST_GATEWAY_TOKEN` (fallback Gateway token; per-user override available)
- `TEST_INBOUND_MODE` (`poll` or `webhook`, default `poll`)
- `TEST_USERS_FILE` (path to a JSON file with users)
- `TEST_USERS_WRITE_FILE` (optional, where registrations are persisted; defaults to `TEST_USERS_FILE`)
- `TEST_USERS` (inline JSON, same shape as users file)
- `TEST_DEFAULT_USER_ID` + `TEST_DEFAULT_USER_TOKEN` (single user shortcut)
- `TEST_ALLOW_REGISTRATION` (`true` or `false`, default `true`)
- `TEST_INVITE_CODES` (comma-separated invite codes; when set, registration requires a valid code)
- `TEST_HMAC_SECRET` (shared HMAC secret for signing webhook + send/poll requests)
- `TEST_REQUIRE_SIGNATURE` (`true` or `false`, default `true` when secret is set)
- `TEST_SIGNATURE_TTL_MS` (signature timestamp window, default `300000`)
- `TEST_SECRET_KEY` (enable token HMAC hashing, recommended)
- `TEST_TRUST_PROXY` (set to `true` behind a reverse proxy)
- `TEST_RATE_LIMIT` (enable rate limiting, default `true`)
- `TEST_SCRYPT_N`/`TEST_SCRYPT_R`/`TEST_SCRYPT_P`/`TEST_SCRYPT_KEY_LEN` (password hash params)

## Invite code modes

You can run with or without invite codes:

- No invite codes: leave `TEST_INVITE_CODES` unset (or empty) and keep
  `TEST_ALLOW_REGISTRATION=true`.
- With invite codes: set `TEST_INVITE_CODES`, for example:

```bash
export TEST_INVITE_CODES="vimalinx-xxxxxxx1,vimalinx-xxxxxxx2"
```

To disable registration entirely:

```bash
export TEST_ALLOW_REGISTRATION=false
```

## Webhook mode

Set the inbound mode to webhook and point the server at the Gateway webhook URL:

```bash
export TEST_INBOUND_MODE=webhook
export TEST_GATEWAY_URL=https://gateway-host:18789/vimalinx-webhook
```

Notes:
- Use HTTPS for public deployments.
- `TEST_GATEWAY_URL` can be set per user in the users file (`gatewayUrl`).
- When `TEST_HMAC_SECRET` is set, requests are signed. Set
  `TEST_REQUIRE_SIGNATURE=true` to enforce signatures.

## Run (Node 22+)

```bash
node server/server.mjs
```

## Deployment notes

- Run behind a process manager (systemd, PM2, etc.) for restarts.
- Make sure the users file is writable when registrations are enabled.
- Protect `/send` with `TEST_SERVER_TOKEN` and/or per-user tokens.
- Set `TEST_SECRET_KEY` to avoid storing tokens in plaintext.
- If you run behind a proxy, set `TEST_TRUST_PROXY=true` to rate-limit by real IP.

## Endpoints

- `GET /` web UI
- `GET /api/stream?userId=...&token=...&lastEventId=...` SSE stream (supports replay)
- `GET /api/poll?userId=...&waitMs=...` long-poll inbound queue (Authorization: Bearer `<user token>`)
- `GET /api/config` config probe
- `POST /api/message` enqueue message (poll mode) or forward to Gateway (webhook mode)
- `POST /api/register` create a new user (invite code required when enabled)
- `POST /api/account/login` login with `userId` + `password`
- `POST /api/token` generate a host token for a user (requires `userId` + `password`)
- `POST /api/token/usage` list token usage for a user (requires `userId` + `password`)
- `POST /api/login` login with token (mobile/Clawdbot)
- `POST /send` receive replies from Gateway (Authorization: Bearer `<user token>` or `TEST_SERVER_TOKEN`)
  - If signatures are enabled, include `x-vimalinx-timestamp`, `x-vimalinx-nonce`, `x-vimalinx-signature`

## Payloads

Send (client -> server):

```json
{ "userId": "alice", "token": "alice-token", "text": "hello" }
```

Inbound to Gateway (server -> gateway):

```json
{
  "message": {
    "chatId": "user:alice",
    "chatType": "dm",
    "senderId": "alice",
    "senderName": "Alice",
    "text": "hello",
    "timestamp": 1710000000
  }
}
```

Inbound poll response (server -> gateway):

```json
{
  "ok": true,
  "messages": [
    {
      "chatId": "user:alice",
      "chatType": "dm",
      "senderId": "alice",
      "senderName": "Alice",
      "text": "hello",
      "timestamp": 1710000000
    }
  ]
}
```

Outbound from Gateway (plugin -> server):

```json
{ "chatId": "user:alice", "text": "hi", "replyToId": "msg-123" }
```

Signature headers (when enabled):

```
x-vimalinx-timestamp: <unix_ms>
x-vimalinx-nonce: <random>
x-vimalinx-signature: HMAC_SHA256(secret, "${timestamp}.${nonce}.${rawBody}")
```

Register payload (client -> server):

```json
{
  "userId": "alice",
  "inviteCode": "alpha-2026",
  "displayName": "Alice",
  "password": "your-password"
}
```

## Security notes

- Passwords are stored with scrypt hashes; legacy plaintext `password` values are auto-migrated.
- With `TEST_SECRET_KEY`, tokens are stored as HMAC hashes (no plaintext in the users file).
- Basic rate limits apply to register/login/token endpoints. Disable with `TEST_RATE_LIMIT=false`.
