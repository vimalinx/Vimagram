# Vimalinx Server

Minimal chat server for the Vimalinx Server channel plugin (`test`).

## Config

Set environment variables:

- `TEST_SERVER_PORT` (default `8788`)
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

User file example (multi-gateway):

```json
{
  "users": [
    {
      "id": "alice",
      "token": "alice-token",
      "displayName": "Alice",
      "gatewayUrl": "http://100.64.0.11:18789/test-webhook",
      "gatewayToken": "alice-token"
    }
  ]
}
```

Notes:
- `gatewayUrl` is required per user when using `TEST_INBOUND_MODE=webhook`.
- `gatewayToken` defaults to `token` if omitted.

## Run (Node 22+)

```bash
node server/server.mjs
```

## Endpoints

- `GET /` web UI
- `GET /api/stream?userId=...&token=...&lastEventId=...` SSE stream (supports replay)
- `GET /api/poll?userId=...&waitMs=...` long-poll inbound queue (Authorization: Bearer `<user token>`)
- `POST /api/message` enqueue message (poll mode) or forward to Gateway (webhook mode)
- `POST /api/register` create a new user (invite code required when enabled)
- `POST /api/account/login` login with `userId` + `password`
- `POST /api/token` generate a host token for a user (requires `userId` + `password`)
- `POST /api/token/usage` list token usage for a user (requires `userId` + `password`)
- `POST /api/login` login with token (mobile/Clawdbot)
- `POST /send` receive replies from Gateway (Authorization: Bearer `<user token>` or `TEST_SERVER_TOKEN`)
  - If signatures are enabled, include `x-test-timestamp`, `x-test-nonce`, `x-test-signature`

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
x-test-timestamp: <unix_ms>
x-test-nonce: <random>
x-test-signature: HMAC_SHA256(secret, "${timestamp}.${nonce}.${rawBody}")
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
