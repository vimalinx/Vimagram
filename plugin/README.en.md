# Vimalinx Server plugin

English | [中文](README.md)

Channel plugin that connects the Gateway to a Vimalinx Server. Supports poll or
webhook inbound modes and token-based auth.

## Requirements

- Gateway running (Node 22+).
- Vimalinx Server reachable at a base URL.

## Install

Simplest (customers): clone the repo and run at the root:

```bash
./install.sh
```

## Configure (wizard)

```bash
clawdbot onboard
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
clawdbot channels status --probe
```

If everything is healthy, the channel should show as connected/polling.

## Related

- Server: `server/README.md`
- Android app: `app/README.md`
