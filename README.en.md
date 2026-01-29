# Vimalinx Suite

English | [中文](README.md)

Vimalinx Suite is the full stack for the Vimalinx Server channel:
- Server: `server`
- Plugin: `plugin`
- Android app (Vimagram): `app`

## One‑click plugin install (Recommended)

Minimal steps for end users:

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

Troubleshooting:
- `curl: (35) TLS connect error`: install system CA certificates and retry (e.g. Debian/Ubuntu/Kali: `sudo apt-get install -y ca-certificates`).

## Run your own server (Optional)

Prereqs: Node 22+ and the CLI.

```bash
npm install -g clawdbot@latest
```

Start the Vimalinx Server:

```bash
export TEST_SERVER_PORT=8788
export TEST_USERS_FILE=/path/to/vimalinx-users.json
export TEST_ALLOW_REGISTRATION=true

node server/server.mjs
```

Start the Gateway:

```bash
clawdbot gateway --port 18789 --verbose
```

## Android app (Vimagram)

```bash
cd app
./gradlew :app:installDebug
```

Then log in to the server, generate a host token in **Account**, and paste it in the
plugin onboarding flow. On the login screen you can tap “Test connection” to
verify reachability; if invites are disabled the UI says the code is not required.

## Docs

- Server: `server/README.md`
- Plugin: `plugin/README.md`
- Android app: `app/README.md`
