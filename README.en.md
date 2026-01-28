# Vimalinx Suite

English | [中文](README.md)

Vimalinx Suite is the full stack for the Vimalinx Server channel:
- Server: `server`
- Plugin: `plugin`
- Android app (Vimagram): `app`

## Quickstart (local)

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

Install + configure the plugin (one-liner after clone):

```bash
./install.sh
```

The script prompts for Server URL + token and writes the config (no onboard needed).
By default it runs: doctor --fix, gateway start, channels status --probe.
Skip with: VIMALINX_SKIP_DOCTOR_FIX=1 / VIMALINX_SKIP_GATEWAY_START=1 / VIMALINX_SKIP_STATUS=1.
To overwrite an existing install: `VIMALINX_FORCE_OVERWRITE=1 ./install.sh`.

Or install manually:

```bash
clawdbot plugins install ./plugin
clawdbot onboard
```

## Offline plugin release (for customers)

Create the offline package:

```bash
./plugin/scripts/pack-release.sh
```

Customer install:

```bash
tar -xzf vimalinx-server-plugin-*.tgz
./package/scripts/install.sh
```

Verify:

```bash
clawdbot channels status --probe
```

## Android app (Vimagram)

```bash
cd app
./gradlew :app:installDebug
```

Then log in to the server, generate a host token in **Account**, and paste it in the
plugin onboarding flow.

## Docs

- Server: `server/README.md`
- Plugin: `plugin/README.md`
- Android app: `app/README.md`
