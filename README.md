# Vimalinx Suite

中文 | [English](README.en.md)

Vimalinx Suite Core 是 Openclaw 的私人服务器解决方案，让机器人（Bots）和移动应用通过自托管服务器通信，无需依赖中心化平台。

**核心组件：**
- **Server**：自托管聊天服务器，提供用户管理、Token 认证、消息收发等功能
- **Plugin**：Gateway 插件，通过轮询（poll）或 webhook 将 Openclaw 连接到你的 Vimalinx 服务器
- **Android App（Vimagram）**：移动端客户端，用于注册、生成 Token、聊天

**适用场景：**
- 为 Openclaw 机器人部署私人聊天基础设施
- 自托管消息服务，不依赖中心化平台
- 通过移动端管理账号和 Token
- 完全掌控你的通信数据

---

## 📱 快速开始（Plugin 用户）

**大多数用户只需要安装 Plugin 即可使用。**

### 前置要求

- 已安装 Node.js 22+
- 已安装并配置 `openclaw` CLI（Gateway 工具）
- 一台 Android 手机（用于获取 Token）

### 安装步骤

#### 步骤 1：安装 openclaw CLI

```bash
npm i -g openclaw
```

#### 步骤 2：配置 openclaw API

首次使用需要配置 API：

```bash
openclaw onboard
```

按照提示输入 API 配置信息。**注意**：在配置 channel 时，如果无需telegram等可以选择 **skip**（跳过），因为后续会通过 `./install.sh` 自动配置 Vimalinx channel。

#### 步骤 3：克隆仓库

```bash
git clone https://github.com/vimalinx/vimalinx-suite-core
cd vimalinx-suite-core
```

#### 步骤 4：在手机上注册并获取 Token

1. 安装 Vimagram App（见下方"Android App 安装"）
2. 启动 Vimagram，默认是`http://123.60.21.129:8788`，如果有第三方服务器点击“添加服务器”
3. 点击 **注册**，填写用户信息
4. 注册成功后，在 **Account** 页面生成 **主机 Token**
5. 复制生成的 Token

**注意**：请妥善保存 Token，它将用于插件认证；为了安全，所有数据都存在本地，清理缓存时候请小心。

#### 步骤 5：运行安装脚本

在项目根目录执行：

```bash
./install.sh
```

脚本会自动执行以下操作：

1. **检查依赖**：验证 `openclaw`、`curl`、`python3` 是否已安装
2. **复制插件**：将 `plugin` 目录复制到 `~/.openclaw/extensions/vimalinx`
3. **配置服务器**：
   - 提示输入 **Vimalinx Server URL**（直接回车使用默认服务器 `http://123.60.21.129:8788`）
   - 提示输入 **Token**（粘贴从手机 App 复制的 Token）
4. **登录验证**：使用 Token 登录服务器，获取 `userId` 和 `token`
5. **写入配置**：自动更新 `~/.openclaw/openclaw.json`，配置 Vimalinx channel
6. **自动步骤**（默认执行）：
- `openclaw doctor --fix`：自动修复依赖问题
- `openclaw gateway stop/start`：重启 Gateway
- `openclaw channels status --probe`：检查连接状态

#### 步骤 6：验证安装

安装脚本会自动运行 `openclaw channels status --probe` 验证连接。如果看到状态显示绿色 **connected/polling**，说明安装成功。

如果需要手动验证：

```bash
openclaw channels status --probe
```

### 高级选项

**跳过自动步骤**：如果不需要自动执行某些步骤，可以设置环境变量：

```bash
# 跳过依赖修复
VIMALINX_SKIP_DOCTOR_FIX=1 ./install.sh

# 跳过 Gateway 重启
VIMALINX_SKIP_GATEWAY_START=1 ./install.sh

# 跳过连接状态检查
VIMALINX_SKIP_STATUS=1 ./install.sh
```

**强制覆盖已安装插件**：

```bash
VIMALINX_FORCE_OVERWRITE=1 ./install.sh
```

**手动指定参数**（跳过交互式输入）：

```bash
# 指定服务器 URL
export VIMALINX_SERVER_URL="http://your-server:8788"

# 指定 Token
export VIMALINX_TOKEN="your-token-here"

# 指定入站模式（poll 或 webhook）
export VIMALINX_INBOUND_MODE="poll"

# 然后执行安装脚本（不会提示输入）
./install.sh
```

### 配置说明

安装脚本会自动配置以下内容到 `~/.openclaw/openclaw.json`：

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

**配置字段说明：**

| 字段 | 说明 | 默认值 |
|------|------|--------|
| `baseUrl` | Vimalinx Server 地址 | - |
| `userId` | 用户 ID（从服务器获取） | - |
| `token` | 认证 Token（从服务器获取） | - |
| `inboundMode` | 入站模式：`poll`（轮询）或 `webhook`（推送） | `poll` |
| `webhookPath` | Webhook 路径（仅 webhook 模式） | `/vimalinx-webhook` |
| `webhookToken` | Webhook 认证 Token（可选） | 等于 `token` |
| `dmPolicy` | 私聊策略：`open`（开放）/ `whitelist`（白名单） | `open` |
| `allowFrom` | 允许发送消息的来源列表 | `["*"]` |

---

## 📲 Android App（Vimagram）

### 安装 App

**方式一：从源码安装（开发者）**

```bash
cd app
./gradlew :app:installDebug
```

**方式二：下载 APK（普通用户）**

联系项目维护者获取 APK 安装包。

### 使用 App

1. **启动 App**：打开 Vimagram
2. **配置服务器**：
   - 输入服务器地址（例如：`http://123.60.21.129:8788`）
   - ~~ 如果使用 HTTPS，请确保服务器证书有效 ~~ 暂未支持
3. **注册账号**：
   - 填写用户名、密码
   - 如果服务器开启了邀请码模式，需要输入邀请码
   - 如果没有的话，
4. **登录**：注册成功后会自动登录，之后可使用账号密码登录
5. **生成 Token**：
   - 进入 **Account** 页面
   - 点击 **生成主机 Token**
   - 复制生成的 Token（用于插件配置）

**注意**：
- Token 仅显示一次，请妥善保存
- 如果需要重新生成，删除旧 Token 后重新生成即可

### 特性

- 直接连接 Vimalinx Server（无需魔法）
- 账号页展示已连接主机 Token，方便恢复
- 支持语言切换（系统/中文/English）

---

## 🚀 Server 部署（高级用户）

如果需要自部署 Vimalinx Server，请参考 `server/README.md`。

### 快速启动（本地测试）

```bash
export TEST_SERVER_PORT=8788
export TEST_USERS_FILE=/path/to/vimalinx-users.json
export TEST_ALLOW_REGISTRATION=true

node server/server.mjs
```

### 生产部署建议

- 使用 systemd/PM2 等保证进程守护
- 开启注册时确保 users 文件可写
- 建议开启 `TEST_SERVER_TOKEN` 或使用用户 token 保护 `/send` 端点
- 公网部署使用 HTTPS

---

## 🔧 详细文档

- **Server 完整文档**：`server/README.md`
- **Plugin 详细配置**：`plugin/README.md`
- **Android App 说明**：`app/README.md`

---

## ❓ 常见问题

### Q1: 安装脚本提示 "openclaw not found in PATH"

**解决方法**：先安装 openclaw CLI

```bash
npm install -g openclaw@latest
```

### Q2: Token 登录失败

**可能原因：**
- Token 无效或已过期
- 服务器地址错误
- 网络连接问题

**解决方法：**
1. 在 Vimagram App 中重新生成 Token
2. 检查服务器地址是否正确（确保包含端口号）
3. 使用 `curl` 测试服务器连接：

```bash
curl -X POST <SERVER_URL>/api/login \
  -H "Content-Type: application/json" \
  -d '{"token":"your-token"}'
```

### Q3: Gateway 连接失败

**检查步骤：**
1. 确认 Gateway 已启动：`openclaw gateway status`
2. 检查 channel 配置：`openclaw channels status --probe`
3. 查看 Gateway 日志：`openclaw gateway logs`

### Q4: 如何更换服务器？

**方法一：重新运行安装脚本**

```bash
./install.sh
```

**方法二：手动修改配置**

编辑 `~/.openclaw/openclaw.json`，修改 `channels.vimalinx.baseUrl` 和 `channels.vimalinx.token`，然后重启 Gateway。

### Q5: 如何切换入站模式（poll/webhook）？

默认使用 `poll`（轮询）模式。如需切换到 `webhook`（推送）模式：

```bash
export VIMALINX_INBOUND_MODE="webhook"
./install.sh
```

注意：webhook 模式需要服务器能够访问 Gateway 的 webhook 端点。

### Q6: 插件已存在，如何重新安装？

```bash
VIMALINX_FORCE_OVERWRITE=1 ./install.sh
```

---

## 📄 许可证

本项目采用 MIT 许可证。
