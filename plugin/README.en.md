# Vimalinx Server plugin

English | [中文](README.md)

Channel plugin that connects the Gateway to a Vimalinx Server. Supports poll or
webhook inbound modes and token-based auth.

## Requirements

- Gateway running (Node 22+).
- Vimalinx Server reachable at a base URL.

## Install

Minimal steps for end users (recommended):

1) Install the Clawdbot CLI (once)

```bash
npm install -g clawdbot@latest
```

2) Run the installer from repo root

```bash
./install.sh
```

3) When prompted, enter:
- Server URL: `https://vimagram.vimalinx.xyz`
- Token: copy from the Vimagram App (Account page)

The script auto-configures and restarts the Gateway.

Optional: non‑interactive install

```bash
VIMALINX_SERVER_URL="https://vimagram.vimalinx.xyz" \
VIMALINX_TOKEN="your-token" \
./install.sh
```

Optional: skip auto steps

```bash
VIMALINX_SKIP_DOCTOR_FIX=1 \
VIMALINX_SKIP_GATEWAY_START=1 \
VIMALINX_SKIP_STATUS=1 \
./install.sh
```

Optional: overwrite existing install

```bash
VIMALINX_FORCE_OVERWRITE=1 ./install.sh
```

## Configure (wizard)

```bash
openclaw onboard
```

The wizard will:
- Ask for the Vimalinx server (official or custom URL).
- Prompt for the token (from Vimagram).
- Let you choose inbound mode (poll or webhook).

## Configure (manual)

Minimal config (single account):

```yaml
channels:
  vimalinx:
    baseUrl: http://server-host:8788
    userId: user-id
    token: host-token
    inboundMode: poll
```

Optional fields:
- `webhookPath` (default `/vimalinx-webhook`)
- `webhookToken` (defaults to `token`)

## Verify

```bash
openclaw channels status --probe
```

If everything is healthy, the channel should show as connected/polling.

## Related

- Server: `server/README.md`
- Android app: `app/README.md`
