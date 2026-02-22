# Vimalinx Server 插件

中文 | [English](README.en.md)

该插件用于连接 Gateway 与 Vimalinx Server，支持轮询（poll）或 webhook 入站模式，并使用 Token 认证。

---

## 🔧 功能特性

- **双向通信**：支持 Gateway 与 Vimalinx Server 之间的消息收发
- **灵活入站模式**：
  - **Poll 模式**（推荐）：插件定期向服务器拉取消息，适合内网环境
  - **Webhook 模式**：服务器主动推送消息到 Gateway，适合公网部署
- **Token 认证**：安全的用户认证机制
- **多账号支持**：可配置多个 Vimalinx Server 用户
- **自动重连**：网络断开时自动尝试重连

---

## 📋 前置要求

- **Node.js 22+**：运行 Gateway 的必需版本
- **openclaw CLI**：已安装并配置好的 Gateway 命令行工具
- **Vimalinx Server**：一个可访问的 Vimalinx Server 实例
- **Token**：从 Vimagram App 获取的用户 Token

---

## 🚀 快速安装

### 方式一：一键安装（推荐）

克隆仓库后在根目录运行安装脚本：

```bash
git clone <repository-url>
cd vimalinx-suite-core
./install.sh
```

安装脚本会自动完成以下操作：

1. **检查依赖**：验证 `openclaw`、`curl`、`python3` 是否已安装
2. **复制插件**：将插件文件复制到 `~/.openclaw/extensions/vimalinx`
3. **交互式配置**：
   - 输入 Vimalinx Server URL（直接回车使用默认服务器）
   - 输入从 Vimagram App 复制的 Token
   - 选择入站模式（poll 或 webhook）
4. **自动登录验证**：使用 Token 登录服务器，获取用户信息
5. **写入配置**：自动更新 `~/.openclaw/openclaw.json`
6. **重启服务**：自动重启 Gateway
7. **验证连接**：检查插件状态

**安装脚本参数说明：**

| 环境变量 | 说明 |
|---------|------|
| `VIMALINX_SERVER_URL` | 指定服务器 URL（跳过交互式输入） |
| `VIMALINX_TOKEN` | 指定 Token（跳过交互式输入） |
| `VIMALINX_INBOUND_MODE` | 指定入站模式：`poll` 或 `webhook` |
| `VIMALINX_FORCE_OVERWRITE` | 强制覆盖已安装插件（值：`1`） |
| `VIMALINX_SKIP_DOCTOR_FIX` | 跳过依赖修复（值：`1`） |
| `VIMALINX_SKIP_GATEWAY_START` | 跳过 Gateway 重启（值：`1`） |
| `VIMALINX_SKIP_STATUS` | 跳过连接状态检查（值：`1`） |

**示例：手动指定参数安装**

```bash
export VIMALINX_SERVER_URL="http://your-server:8788"
export VIMALINX_TOKEN="your-token-here"
export VIMALINX_INBOUND_MODE="poll"
./install.sh
```

### 方式二：使用向导配置

如果已安装插件，可以使用 `openclaw onboard` 向导进行配置：

```bash
openclaw onboard
```

向导会要求：
- 选择服务器（官方服务器 / 自定义 URL）
- 输入 Vimagram 生成的 Token
- 选择入站模式（poll 或 webhook）

### 方式三：手动配置

手动编辑 `~/.openclaw/openclaw.json` 文件。

**最小配置（单账号）：**

```json
{
  "channels": {
    "vimalinx": {
      "baseUrl": "http://server-host:8788",
      "userId": "user-id",
      "token": "host-token",
      "inboundMode": "poll",
      "enabled": true
    }
  }
}
```

**完整配置示例：**

```json
{
  "channels": {
    "vimalinx": {
      "enabled": true,
      "baseUrl": "http://123.60.21.129:8788",
      "userId": "alice",
      "token": "alice-token-here",
      "inboundMode": "poll",
      "dmPolicy": "open",
      "allowFrom": ["*"],
      "webhookPath": "/vimalinx-webhook",
      "webhookToken": "webhook-token-here"
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

---

## ⚙️ 配置说明

### 通道配置字段

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `enabled` | boolean | 否 | `true` | 是否启用该通道 |
| `baseUrl` | string | 是 | - | Vimalinx Server 地址（包含协议和端口） |
| `userId` | string | 是 | - | 用户 ID（从服务器获取） |
| `token` | string | 是 | - | 认证 Token（从 Vimagram App 获取） |
| `inboundMode` | string | 否 | `poll` | 入站模式：`poll` 或 `webhook` |
| `webhookPath` | string | 否 | `/vimalinx-webhook` | Webhook 接收路径（webhook 模式） |
| `webhookToken` | string | 否 | 等于 `token` | Webhook 认证 Token（webhook 模式） |
| `dmPolicy` | string | 否 | `open` | 私聊策略：`open` 或 `whitelist` |
| `allowFrom` | string[] | 否 | `["*"]` | 允许发送消息的来源列表 |

### 入站模式说明

#### Poll 模式（推荐）

- **工作原理**：插件定期向 Vimalinx Server 发起长轮询请求，拉取新消息
- **适用场景**：
  - Gateway 在内网，无法被服务器主动访问
  - 网络不稳定，需要插件主动拉取
  - 简化配置，无需开放端口
- **配置示例**：

```json
{
  "inboundMode": "poll"
}
```

#### Webhook 模式

- **工作原理**：Vimalinx Server 主动推送消息到 Gateway 的 webhook 端点
- **适用场景**：
  - Gateway 在公网或有公网 IP
  - 需要更实时的消息推送
  - 服务器需要主动触发某些操作
- **配置示例**：

```json
{
  "inboundMode": "webhook",
  "webhookPath": "/vimalinx-webhook",
  "webhookToken": "secure-webhook-token"
}
```

**注意**：使用 Webhook 模式时，需要确保 Vimalinx Server 能够访问 Gateway 的 webhook 端点。

---

## ✅ 验证安装

### 检查插件状态

```bash
openclaw channels status --probe
```

**正常输出示例：**

```
Channel: vimalinx
Status: connected
Mode: polling
UserId: alice
Server: http://123.60.21.129:8788
```

### 检查 Gateway 日志

```bash
openclaw gateway logs
```

查看插件加载和连接相关的日志信息。

### 测试消息收发

1. 在 Vimagram App 中发送一条测试消息
2. 在 Gateway 中检查是否收到消息
3. 在 Gateway 中回复消息，检查 Vimagram App 是否收到

---

## 🔄 更新和重新安装

### 重新安装插件

如果插件文件已更新，需要重新安装：

```bash
VIMALINX_FORCE_OVERWRITE=1 ./install.sh
```

### 更新配置

如果需要修改服务器或 Token，重新运行安装脚本：

```bash
export VIMALINX_SERVER_URL="http://new-server:8788"
export VIMALINX_TOKEN="new-token"
./install.sh
```

或者手动编辑 `~/.openclaw/openclaw.json`，然后重启 Gateway：

```bash
openclaw gateway restart
```

---

## 🐛 故障排查

### 问题 1：插件无法启动

**症状**：`openclaw channels status` 显示插件未加载

**解决方法**：
1. 检查插件文件是否存在：`ls ~/.openclaw/extensions/vimalinx`
2. 检查 Gateway 配置中是否启用了插件：
   ```bash
   openclaw config
   ```
3. 查看 Gateway 日志：`openclaw gateway logs`
4. 运行依赖检查：`openclaw doctor --fix`

### 问题 2：连接失败（connected 状态异常）

**症状**：`openclaw channels status --probe` 显示连接失败

**可能原因和解决方法：**

1. **服务器地址错误**
   - 检查 `baseUrl` 是否正确（包含协议 `http://` 或 `https://`）
   - 使用 `curl` 测试服务器是否可访问：
     ```bash
     curl http://your-server:8788/
     ```

2. **Token 无效**
   - 在 Vimagram App 中重新生成 Token
   - 确认 Token 没有被截断或复制错误

3. **网络问题**
   - 检查防火墙设置
   - 确认服务器是否在运行

4. **用户 ID 不匹配**
   - 运行登录测试：
     ```bash
     curl -X POST http://your-server:8788/api/login \
       -H "Content-Type: application/json" \
       -d '{"token":"your-token"}'
     ```

### 问题 3：消息无法收发

**症状**：连接正常，但消息无法发送或接收

**解决方法**：
1. **检查入站模式**：确认 `inboundMode` 配置正确
2. **Webhook 模式**：检查服务器是否能访问 Gateway 的 webhook 端点
3. **权限配置**：检查 `dmPolicy` 和 `allowFrom` 配置
4. **查看详细日志**：
   ```bash
   openclaw gateway logs --level debug
   ```

### 问题 4：安装脚本执行失败

**症状**：`./install.sh` 报错

**常见错误和解决方法：**

1. **`openclaw not found in PATH`**
- 安装 CLI：`npm install -g openclaw@latest`

2. **`python3 not found in PATH`**
   - 安装 Python 3

3. **`curl not found in PATH`**
   - 安装 curl

4. **`Target already exists`**
   - 强制覆盖：`VIMALINX_FORCE_OVERWRITE=1 ./install.sh`

5. **`Login failed`**
   - 检查 Token 是否正确
   - 检查服务器地址是否正确
   - 检查网络连接

---

## 📚 相关文档

- **主 README**：`../README.md`
- **Server 文档**：`../server/README.md`
- **Android App 文档**：`../app/README.md`

---

## 💡 使用技巧

1. **使用环境变量**：在 CI/CD 或自动化脚本中，可以通过环境变量跳过交互式输入
2. **多账号配置**：可以配置多个 Vimalinx 通道，每个对应不同的用户和服务器
3. **安全建议**：
   - 定期更换 Token
   - 使用 HTTPS 访问服务器
   - 在公网部署时开启 `TEST_SERVER_TOKEN` 保护 `/send` 端点
4. **性能优化**：
   - 根据网络状况调整轮询间隔
   - 使用 Webhook 模式获得更实时的消息推送

---

## 📄 许可证

本项目采用 MIT 许可证。
