# Vimalinx Server

中文 | [English](README.en.md)

Vimalinx Server 是一个轻量级的聊天服务器，为 Gateway 插件提供消息通道服务（channel id: `vimalinx`）。

---

## ✨ 功能特性

- **用户管理**：支持用户注册、登录、Token 生成
- **消息收发**：支持双向消息传递
- **灵活入站模式**：
  - **Poll 模式**：长轮询方式拉取消息
  - **Webhook 模式**：主动推送消息到 Gateway
- **Token 认证**：安全的用户和主机 Token 认证
- **邀请码系统**：支持邀请码注册模式
- **签名验证**：可选 HMAC-SHA256 签名验证
- **Web UI**：提供简单的 Web 界面
- **RESTful API**：完整的 HTTP API 接口

---

## 📋 系统要求

- **Node.js 22+**：必需版本
- **操作系统**：Linux / macOS / Windows
- **内存**：建议 512MB 以上
- **磁盘空间**：建议 100MB 以上（含日志和用户数据）

---

## 🚀 快速启动

### 本地开发模式（Poll 模式）

```bash
export TEST_SERVER_PORT=8788
export TEST_USERS_FILE=/path/to/vimalinx-users.json
export TEST_ALLOW_REGISTRATION=true

node server/server.mjs
```

启动后，访问 `http://localhost:8788` 可以查看 Web UI。

**说明：**
- `TEST_SERVER_PORT`：服务器监听端口（默认 `8788`）
- `TEST_USERS_FILE`：用户数据持久化文件路径
- `TEST_ALLOW_REGISTRATION`：是否允许开放注册（`true`/`false`）

---

## ⚙️ 配置说明

### 环境变量配置

| 环境变量 | 类型 | 默认值 | 说明 |
|---------|------|--------|------|
| `TEST_SERVER_PORT` | number | `8788` | 服务器监听端口 |
| `TEST_SERVER_TOKEN` | string | - | 全局 `/send` 端点密钥（可选） |
| `TEST_GATEWAY_URL` | string | - | Gateway webhook URL（webhook 模式） |
| `TEST_GATEWAY_TOKEN` | string | - | Gateway 认证 Token |
| `TEST_INBOUND_MODE` | string | `poll` | 入站模式：`poll` 或 `webhook` |
| `TEST_USERS_FILE` | string | - | 用户数据文件路径 |
| `TEST_USERS_WRITE_FILE` | string | 同 `TEST_USERS_FILE` | 注册时写入的用户文件路径 |
| `TEST_USERS` | JSON | - | 内联用户数据（JSON 字符串） |
| `TEST_DEFAULT_USER_ID` | string | - | 默认用户 ID |
| `TEST_DEFAULT_USER_TOKEN` | string | - | 默认用户 Token |
| `TEST_ALLOW_REGISTRATION` | boolean | `false` | 是否允许开放注册 |
| `TEST_INVITE_CODES` | string[] | - | 邀请码列表（逗号分隔） |
| `TEST_HMAC_SECRET` | string | - | HMAC 签名密钥 |
| `TEST_REQUIRE_SIGNATURE` | boolean | `true`（当 secret 存在时） | 是否要求请求签名 |
| `TEST_SIGNATURE_TTL_MS` | number | `300000` | 签名时间窗口（毫秒） |

### 用户数据文件格式

用户数据文件（`TEST_USERS_FILE`）使用 JSON 格式存储：

```json
{
  "alice": {
    "userId": "alice",
    "displayName": "Alice",
    "passwordHash": "hashed-password",
    "tokens": {
      "host-abc123": {
        "token": "host-abc123",
        "createdAt": 1710000000000
      }
    },
    "gatewayUrl": "https://gateway-host:18789/vimalinx-webhook"
  }
}
```

**字段说明：**

- `userId`：用户 ID
- `displayName`：显示名称
- `passwordHash`：密码哈希（不存储明文密码）
- `tokens`：该用户生成的所有 Token
  - `token`：Token 字符串
  - `createdAt`：创建时间（毫秒时间戳）
- `gatewayUrl`：单个用户的 Gateway webhook URL（可选，覆盖全局配置）

---

## 🔐 注册模式

### 开放注册（推荐用于开发测试）

允许任何人注册账号：

```bash
export TEST_ALLOW_REGISTRATION=true
```

**注意**：生产环境建议关闭开放注册或使用邀请码模式。

### 邀请码模式（推荐用于生产）

只有持有有效邀请码的用户才能注册：

```bash
export TEST_ALLOW_REGISTRATION=true
export TEST_INVITE_CODES="vimalinx-xxxxxxx1,vimalinx-xxxxxxx2"
```

**邀请码管理：**

1. 生成邀请码（建议使用随机字符串）
2. 通过安全渠道分发给目标用户
3. 用户注册时需要输入有效邀请码

### 关闭注册

完全关闭注册功能，只能由管理员手动添加用户：

```bash
export TEST_ALLOW_REGISTRATION=false
```

---

## 📡 入站模式

### Poll 模式（推荐）

**工作原理**：Gateway 定期向服务器发起长轮询请求，拉取新消息。

**优点：**
- 适用于内网环境（Gateway 无法被服务器访问）
- 网络不稳定时更可靠
- 配置简单，无需开放端口

**缺点：**
- 消息延迟取决于轮询间隔
- 服务器需要处理持续的轮询请求

**配置：**

```bash
export TEST_INBOUND_MODE=poll
```

### Webhook 模式

**工作原理**：服务器主动推送消息到 Gateway 的 webhook 端点。

**优点：**
- 消息推送实时性高
- 减少服务器负载（无需处理轮询请求）

**缺点：**
- 需要服务器能够访问 Gateway（需要公网 IP 或内网穿透）
- 配置稍复杂

**配置：**

```bash
export TEST_INBOUND_MODE=webhook
export TEST_GATEWAY_URL=https://gateway-host:18789/vimalinx-webhook
export TEST_GATEWAY_TOKEN="gateway-token-here"
```

**单个用户配置不同的 Gateway URL：**

在用户数据文件中为每个用户单独设置 `gatewayUrl`：

```json
{
  "alice": {
    "userId": "alice",
    "gatewayUrl": "https://gateway-alice:18789/vimalinx-webhook"
  },
  "bob": {
    "userId": "bob",
    "gatewayUrl": "https://gateway-bob:18789/vimalinx-webhook"
  }
}
```

---

## 🔒 安全配置

### 全局 `/send` 端点保护

启用全局 Token 来保护 `/send` 端点：

```bash
export TEST_SERVER_TOKEN="your-global-secret-token"
```

启用后，所有 `/send` 请求都需要在请求体中包含 `serverToken`：

```json
{
  "serverToken": "your-global-secret-token",
  "chatId": "user:alice",
  "text": "hello"
}
```

### HMAC 签名验证

启用请求签名验证，防止请求伪造：

```bash
export TEST_HMAC_SECRET="your-hmac-secret-key"
```

启用后，服务器会验证以下签名头：

```
x-vimalinx-timestamp: <unix_ms>
x-vimalinx-nonce: <random>
x-vimalinx-signature: HMAC_SHA256(secret, "${timestamp}.${nonce}.${rawBody}")
```

**签名计算示例（JavaScript）：**

```javascript
const crypto = require('crypto');

function generateSignature(secret, timestamp, nonce, body) {
  const data = `${timestamp}.${nonce}.${body}`;
  return crypto.createHmac('sha256', secret).update(data).digest('hex');
}

// 使用示例
const timestamp = Date.now();
const nonce = Math.random().toString(36).substring(7);
const body = JSON.stringify({ userId: 'alice', text: 'hello' });

const signature = generateSignature(
  'your-hmac-secret-key',
  timestamp,
  nonce,
  body
);

console.log(`x-vimalinx-timestamp: ${timestamp}`);
console.log(`x-vimalinx-nonce: ${nonce}`);
console.log(`x-vimalinx-signature: ${signature}`);
```

**调整签名时间窗口：**

```bash
export TEST_SIGNATURE_TTL_MS=600000  # 10 分钟
```

---

## 🌐 API 端点

### Web 界面

- `GET /` - Web UI

### 轮询接口

- `GET /api/poll` - 长轮询获取消息
  - Query 参数：`userId`, `token`, `timeout`（毫秒）

### Webhook 接入

- `POST /vimalinx-webhook` - 接收来自服务器的消息推送

### 消息接口

- `POST /api/message` - 入站消息（客户端 -> 服务器）
- `POST /send` - Gateway 出站消息（Gateway -> 服务器）

### 用户认证

- `POST /api/register` - 用户注册
- `POST /api/account/login` - 账号密码登录
- `POST /api/login` - Token 登录（获取用户信息）
- `POST /api/token` - 生成主机 Token
- `POST /api/token/usage` - Token 使用统计

### SSE 流

- `GET /api/stream` - Server-Sent Events 消息流

---

## 📝 API 请求示例

### 用户注册

```bash
curl -X POST http://localhost:8788/api/register \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "alice",
    "inviteCode": "vimalinx-xxxxxxx1",
    "displayName": "Alice",
    "password": "your-password"
  }'
```

**响应示例：**

```json
{
  "ok": true,
  "userId": "alice"
}
```

### 账号密码登录

```bash
curl -X POST http://localhost:8788/api/account/login \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "alice",
    "password": "your-password"
  }'
```

**响应示例：**

```json
{
  "ok": true,
  "userId": "alice",
  "displayName": "Alice"
}
```

### 生成主机 Token

```bash
curl -X POST http://localhost:8788/api/token \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "alice",
    "password": "your-password"
  }'
```

**响应示例：**

```json
{
  "ok": true,
  "token": "host-abc123def456",
  "userId": "alice"
}
```

### Token 登录

```bash
curl -X POST http://localhost:8788/api/login \
  -H "Content-Type: application/json" \
  -d '{
    "token": "host-abc123def456"
  }'
```

**响应示例：**

```json
{
  "ok": true,
  "userId": "alice",
  "displayName": "Alice",
  "token": "host-abc123def456"
}
```

### 发送消息（客户端 -> 服务器）

```bash
curl -X POST http://localhost:8788/api/message \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "alice",
    "token": "host-abc123def456",
    "text": "hello world"
  }'
```

**响应示例：**

```json
{
  "ok": true,
  "messageId": "msg-123456"
}
```

### Poll 获取消息（服务器 -> Gateway）

```bash
curl -X GET "http://localhost:8788/api/poll?userId=alice&token=host-abc123def456&timeout=30000"
```

**响应示例：**

```json
{
  "ok": true,
  "messages": [
    {
      "chatId": "user:alice",
      "chatType": "dm",
      "senderId": "alice",
      "senderName": "Alice",
      "text": "hello world",
      "timestamp": 1710000000000
    }
  ]
}
```

### 出站消息（Gateway -> 服务器）

```bash
curl -X POST http://localhost:8788/send \
  -H "Content-Type: application/json" \
  -d '{
    "serverToken": "your-global-secret-token",
    "chatId": "user:alice",
    "text": "hi there",
    "replyToId": "msg-123456"
  }'
```

**响应示例：**

```json
{
  "ok": true,
  "messageId": "msg-789012"
}
```

---

## 🚀 部署建议

### 使用 PM2（推荐）

PM2 是一个流行的 Node.js 进程管理器：

```bash
# 安装 PM2
npm install -g pm2

# 启动服务器
pm2 start server/server.mjs --name vimalinx-server

# 查看状态
pm2 status

# 查看日志
pm2 logs vimalinx-server

# 重启
pm2 restart vimalinx-server

# 停止
pm2 stop vimalinx-server

# 设置开机自启
pm2 startup
pm2 save
```

### 使用 Systemd

创建 systemd 服务文件 `/etc/systemd/system/vimalinx-server.service`：

```ini
[Unit]
Description=Vimalinx Server
After=network.target

[Service]
Type=simple
User=vimalinx
WorkingDirectory=/path/to/vimalinx-suite-core
Environment="TEST_SERVER_PORT=8788"
Environment="TEST_USERS_FILE=/var/lib/vimalinx/users.json"
Environment="TEST_ALLOW_REGISTRATION=true"
ExecStart=/usr/bin/node server/server.mjs
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

启用服务：

```bash
# 创建用户和目录
sudo useradd -r -s /bin/false vimalinx
sudo mkdir -p /var/lib/vimalinx
sudo chown vimalinx:vimalinx /var/lib/vimalinx

# 复制服务文件
sudo cp vimalinx-server.service /etc/systemd/system/

# 重载 systemd
sudo systemctl daemon-reload

# 启动服务
sudo systemctl start vimalinx-server

# 设置开机自启
sudo systemctl enable vimalinx-server

# 查看状态
sudo systemctl status vimalinx-server

# 查看日志
sudo journalctl -u vimalinx-server -f
```

### 使用 Docker

创建 `Dockerfile`：

```dockerfile
FROM node:22-alpine

WORKDIR /app

COPY package*.json ./
RUN npm ci --only=production

COPY server ./server

ENV TEST_SERVER_PORT=8788
ENV TEST_USERS_FILE=/data/users.json
ENV TEST_ALLOW_REGISTRATION=true

VOLUME /data

EXPOSE 8788

CMD ["node", "server/server.mjs"]
```

构建并运行：

```bash
# 构建镜像
docker build -t vimalinx-server .

# 运行容器
docker run -d \
  --name vimalinx-server \
  -p 8788:8788 \
  -v /path/to/data:/data \
  -e TEST_SERVER_PORT=8788 \
  -e TEST_USERS_FILE=/data/users.json \
  -e TEST_ALLOW_REGISTRATION=true \
  --restart unless-stopped \
  vimalinx-server
```

使用 Docker Compose：

```yaml
version: '3.8'

services:
  vimalinx-server:
    build: .
    container_name: vimalinx-server
    ports:
      - "8788:8788"
    volumes:
      - ./data:/data
    environment:
      - TEST_SERVER_PORT=8788
      - TEST_USERS_FILE=/data/users.json
      - TEST_ALLOW_REGISTRATION=true
      - TEST_HMAC_SECRET=${HMAC_SECRET}
    restart: unless-stopped
```

运行：

```bash
docker-compose up -d
```

---

## 🛡️ 安全最佳实践

1. **使用 HTTPS**
   - 在生产环境中使用 HTTPS
   - 配置有效的 SSL/TLS 证书
   - 使用 Nginx 或 Caddy 作为反向代理

2. **保护敏感端点**
   - 启用 `TEST_SERVER_TOKEN` 保护 `/send` 端点
   - 使用用户 Token 保护用户操作
   - 启用 HMAC 签名验证

3. **注册控制**
   - 生产环境关闭开放注册
   - 使用邀请码模式
   - 实现邮箱或手机验证（需要自行扩展）

4. **密码安全**
   - 使用强密码策略
   - 密码使用哈希存储（已实现）
   - 考虑实现密码重置功能（需要自行扩展）

5. **防火墙配置**
   - 仅开放必要的端口（8788）
   - 使用防火墙限制访问 IP

6. **定期备份**
   - 定期备份用户数据文件
   - 备份重要的环境变量配置

7. **监控和日志**
   - 监控服务器资源使用
   - 定期检查日志文件
   - 设置异常告警

---

## 🐛 故障排查

### 问题 1：服务器无法启动

**症状**：运行 `node server/server.mjs` 后报错退出

**可能原因和解决方法：**

1. **端口被占用**
   ```bash
   # 检查端口占用
   lsof -i :8788  # macOS/Linux
   netstat -ano | findstr :8788  # Windows

   # 更换端口
   export TEST_SERVER_PORT=8789
   ```

2. **用户文件权限问题**
   ```bash
   # 检查文件权限
   ls -la /path/to/vimalinx-users.json

   # 修改权限
   chmod 644 /path/to/vimalinx-users.json
   ```

3. **Node.js 版本不兼容**
   ```bash
   # 检查 Node.js 版本
   node --version

   # 升级到 22+
   nvm install 22
   nvm use 22
   ```

### 问题 2：无法连接到服务器

**症状**：客户端无法连接到服务器

**解决方法：**

1. **检查服务器是否运行**
   ```bash
   curl http://localhost:8788/
   ```

2. **检查防火墙设置**
   ```bash
   # Linux (ufw)
   sudo ufw allow 8788/tcp

   # Linux (firewalld)
   sudo firewall-cmd --permanent --add-port=8788/tcp
   sudo firewall-cmd --reload
   ```

3. **检查网络配置**
   - 确认服务器 IP 地址正确
   - 确认端口没有在内网和公网之间被 NAT 阻挡

### 问题 3：消息无法收发

**症状**：连接正常，但消息无法发送或接收

**解决方法：**

1. **检查 Token 是否有效**
   ```bash
   curl -X POST http://localhost:8788/api/login \
     -H "Content-Type: application/json" \
     -d '{"token":"your-token"}'
   ```

2. **检查入站模式配置**
   - Poll 模式：确认 Gateway 正在轮询
   - Webhook 模式：确认服务器能访问 Gateway

3. **查看服务器日志**
   ```bash
   # PM2
   pm2 logs vimalinx-server

   # Systemd
   sudo journalctl -u vimalinx-server -f

   # Docker
   docker logs -f vimalinx-server
   ```

---

## 📚 相关文档

- **主 README**：`../README.md`
- **Plugin 文档**：`../plugin/README.md`
- **Android App 文档**：`../app/README.md`

---

## 📄 许可证

本项目采用 MIT 许可证。
