# vimalinx-suite-core

Open-source core for Vimalinx Server (server + Clawdbot plugin + Vimagram Android app).

## Layout

- `server/` Vimalinx Server (Node 22+)
- `plugin/` Clawdbot channel plugin
- `app/` Vimagram Android app

## Quick start

### Server

```bash
cd server
node server.mjs
```

Configure via env vars. See `server/README.md` and `server/.env.example`.

### Plugin (Clawdbot)

```bash
cd plugin
bash scripts/install-local.sh
```

Then run `clawdbot quickstart` and select **Vimalinx Server**, paste your token.

### Android app

```bash
cd app
./gradlew :app:installDebug
```

## License

This project is licensed under the GNU AGPLv3. Commercial licensing is available.
See `LICENSE` and `COMMERCIAL_LICENSE.md`.
