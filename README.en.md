# Vimalinx Suite

English | [中文](README.md)

Vimalinx Suite Core is a private server solution for Openclaw, enabling bots and mobile apps to communicate through self-hosted servers instead of centralized platforms.

**Core Components:**
- **Server**: Self-hosted chat server with user management, token auth, and messaging
- **Plugin**: Gateway plugin connecting Openclaw to your Vimalinx server via poll or webhook
- **Android App (Vimagram)**: Mobile client for registration, token generation, and messaging

**Use Cases:**
- Deploy private chat infrastructure for Openclaw bots
- Self-host messaging without relying on centralized platforms
- Manage accounts and tokens via mobile app
- Full control over your communication data

---

## 📱 Quick Start (Plugin Users)

**Most users only need to install the Plugin.**

### Prerequisites

- Node.js 22+ installed
- `openclaw` CLI installed and configured (Gateway tool)
- An Android phone (for getting tokens)

### Installation Steps

#### Step 1: Install openclaw CLI

```bash
npm install -g openclaw@latest
```

**openclaw** is the Gateway CLI tool for managing plugins, channels, and message routing.

#### Step 2: Configure openclaw API

First-time setup requires API configuration:

```bash
openclaw config
```

Follow the prompts to enter API configuration. **Important**: When configuring channels, select **skip** since the `./install.sh` script will automatically configure the Vimalinx channel.

#### Step 3: Clone the Repository

```bash
git clone <repository-url>
cd vimalinx-suite-core
```

#### Step 4: Register on Mobile and Get Token

1. Install Vimagram App (see "Android App Installation" below)
2. Launch Vimagram and enter server address (e.g., `http://123.60.21.129:8788`)
3. Click **Register**, fill in user information
4. After successful registration, go to **Account** page and generate **Host Token**
5. Copy the generated token

**Note**: Keep the token safe—it will be used for plugin authentication.

#### Step 5: Run Installation Script

Execute from project root:

```bash
./install.sh
```

The script will automatically:

1. **Check dependencies**: Verify `openclaw`, `curl`, `python3` are installed
2. **Copy plugin**: Copy `plugin` directory to `~/.openclaw/extensions/vimalinx`
3. **Configure server**:
   - Prompt for **Vimalinx Server URL** (press Enter for default server `http://123.60.21.129:8788`)
   - Prompt for **Token** (paste token copied from mobile app)
4. **Login verification**: Use token to login to server and get `userId` and `token`
5. **Write config**: Automatically update `~/.openclaw/openclaw.json` with Vimalinx channel config
6. **Auto steps** (default):
   - `openclaw doctor --fix`: Auto-fix dependency issues
   - `openclaw gateway stop/start`: Restart Gateway
   - `openclaw channels status --probe`: Check connection status

#### Step 6: Verify Installation

The install script will automatically run `openclaw channels status --probe` to verify the connection. If you see **connected/polling**, installation is successful.

For manual verification:

```bash
openclaw channels status --probe
```

### Advanced Options

**Skip auto steps**: Use environment variables to skip certain auto steps:

```bash
# Skip dependency fix
VIMALINX_SKIP_DOCTOR_FIX=1 ./install.sh

# Skip Gateway restart
VIMALINX_SKIP_GATEWAY_START=1 ./install.sh

# Skip connection status check
VIMALINX_SKIP_STATUS=1 ./install.sh
```

**Force overwrite existing plugin**:

```bash
VIMALINX_FORCE_OVERWRITE=1 ./install.sh
```

**Specify parameters manually** (skip interactive input):

```bash
# Specify server URL
export VIMALINX_SERVER_URL="http://your-server:8788"

# Specify token
export VIMALINX_TOKEN="your-token-here"

# Specify inbound mode (poll or webhook)
export VIMALINX_INBOUND_MODE="poll"

# Then run install script (won't prompt for input)
./install.sh
```

### Configuration Fields

The install script automatically configures `~/.openclaw/openclaw.json`:

```json
{
  "channels": {
    "vimalinx": {
      "enabled": true,
      "baseUrl": "http://123.60.21.129:8788",
      "userId": "your-user-id",
      "token": "your-token",
      "inboundMode": "poll",
      "dmPolicy": "open",
      "allowFrom": ["*"]
    }
  },
  "plugins": {
    "entries": {
      "vimalinx": {
        "enabled": true
      }
    }
  }
}
```

**Configuration Field Descriptions:**

| Field | Description | Default |
|-------|-------------|---------|
| `baseUrl` | Vimalinx Server address | - |
| `userId` | User ID (obtained from server) | - |
| `token` | Authentication token (obtained from server) | - |
| `inboundMode` | Inbound mode: `poll` (pull) or `webhook` (push) | `poll` |
| `webhookPath` | Webhook path (webhook mode only) | `/vimalinx-webhook` |
| `webhookToken` | Webhook auth token (optional) | equals `token` |
| `dmPolicy` | DM policy: `open` / `whitelist` | `open` |
| `allowFrom` | Allowed sender list | `["*"]` |

---

## 📲 Android App (Vimagram)

### Install App

**Method 1: Install from Source (Developers)**

```bash
cd app
./gradlew :app:installDebug
```

**Method 2: Download APK (Regular Users)**

Contact the project maintainer for the latest APK installation package.

### Using the App

1. **Launch App**: Open Vimagram
2. **Configure Server**:
   - Enter server address (e.g., `http://123.60.21.129:8788`)
   - If using HTTPS, ensure server certificate is valid
3. **Register Account**:
   - Fill in username and password
   - If server has invite code mode enabled, enter invite code
4. **Login**: After registration, you'll be auto-logged in. Use username/password for future logins.
5. **Generate Token**:
   - Go to **Account** page
   - Click **Generate Host Token**
   - Copy generated token (for plugin configuration)

**Note**:
- Token is displayed only once—copy and save it immediately
- If you need to regenerate, delete old token and generate new one

### Features

- Direct connection to Vimalinx Server (not through Gateway)
- Account page displays connected host tokens for easy recovery
- Multi-language support (System/Chinese/English)
- DM message sending and receiving

---

## 🚀 Server Deployment (Advanced Users)

If you need to self-host Vimalinx Server, see `server/README.md`.

### Quick Start (Local Test)

```bash
export TEST_SERVER_PORT=8788
export TEST_USERS_FILE=/path/to/vimalinx-users.json
export TEST_ALLOW_REGISTRATION=true

node server/server.mjs
```

### Production Deployment Recommendations

- Use process managers like systemd/PM2 for daemon
- Ensure users file is writable when registration is enabled
- Recommend enabling `TEST_SERVER_TOKEN` or using user tokens to protect `/send` endpoint
- Use HTTPS for public deployment

---

## 🔧 Detailed Documentation

- **Server Full Documentation**: `server/README.md`
- **Plugin Detailed Configuration**: `plugin/README.md`
- **Android App Guide**: `app/README.md`

---

## ❓ FAQ

### Q1: Install script says "openclaw not found in PATH"

**Solution**: Install openclaw CLI first

```bash
npm install -g openclaw@latest
```

### Q2: Token login fails

**Possible reasons:**
- Token is invalid or expired
- Server address is incorrect
- Network connection issues

**Solutions:**
1. Regenerate token in Vimagram App
2. Check if server address is correct (include port number)
3. Test server connection with curl:

```bash
curl -X POST <SERVER_URL>/api/login \
  -H "Content-Type: application/json" \
  -d '{"token":"your-token"}'
```

### Q3: Gateway connection fails

**Troubleshooting steps:**
1. Confirm Gateway is running: `openclaw gateway status`
2. Check channel configuration: `openclaw channels status --probe`
3. View Gateway logs: `openclaw gateway logs`

### Q4: How to switch servers?

**Method 1: Re-run install script**

```bash
export VIMALINX_SERVER_URL="http://new-server:8788"
export VIMALINX_TOKEN="new-token"
./install.sh
```

**Method 2: Manual config edit**

Edit `~/.openclaw/openclaw.json`, modify `channels.vimalinx.baseUrl` and `channels.vimalinx.token`, then restart Gateway.

### Q5: How to switch inbound mode (poll/webhook)?

Default is `poll` (pull) mode. To switch to `webhook` (push) mode:

```bash
export VIMALINX_INBOUND_MODE="webhook"
./install.sh
```

Note: Webhook mode requires server to access Gateway's webhook endpoint.

### Q6: Plugin already exists, how to reinstall?

```bash
VIMALINX_FORCE_OVERWRITE=1 ./install.sh
```

---

## 📄 License

This project is licensed under MIT License.
