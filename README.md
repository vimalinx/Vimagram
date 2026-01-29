# Vimalinx Suite

中文 | [English](README.en.md)

Vimalinx Suite 是 Vimalinx Server 通道的全栈实现：
- Server：`server`
- Plugin：`plugin`
- Android App（Vimagram）：`app`

## 一键安装插件（推荐）

面向最终用户的最简流程：

1) 安装 Clawdbot（只需一次）

```bash
npm install -g clawdbot@latest
```

2) 在仓库根目录运行一键安装

```bash
./install.sh
```

3) 按提示输入
- 服务器地址：`https://vimagram.vimalinx.xyz`
- Token：在 Vimagram App 的 Account 页面生成/复制

完成后脚本会自动配置并重启 Gateway。

可选：不交互模式

```bash
VIMALINX_SERVER_URL="https://vimagram.vimalinx.xyz" \
VIMALINX_TOKEN="你的Token" \
./install.sh
```

可选：跳过自动步骤

```bash
VIMALINX_SKIP_DOCTOR_FIX=1 \
VIMALINX_SKIP_GATEWAY_START=1 \
VIMALINX_SKIP_STATUS=1 \
./install.sh
```

可选：覆盖已有插件安装

```bash
VIMALINX_FORCE_OVERWRITE=1 ./install.sh
```

常见问题：
- `curl: (35) TLS connect error`：先安装系统 CA 证书后重试（如 Debian/Ubuntu/Kali：`sudo apt-get install -y ca-certificates`）。

## 自建服务器（可选）

准备：安装 Node 22+，并全局安装 CLI。

```bash
npm install -g clawdbot@latest
```

启动 Vimalinx Server：

```bash
export TEST_SERVER_PORT=8788
export TEST_USERS_FILE=/path/to/vimalinx-users.json
export TEST_ALLOW_REGISTRATION=true

node server/server.mjs
```

启动 Gateway：

```bash
clawdbot gateway --port 18789 --verbose
```

## Android App（Vimagram）

```bash
cd app
./gradlew :app:installDebug
```

登录服务器后，在 **Account** 生成主机 Token，并在插件向导里粘贴。
登录页可先点「连接测试」确认服务器可达；若服务器未启用邀请码，界面会提示无需填写。

## 使用说明

- Server：`server/README.md`
- Plugin：`plugin/README.md`
- Android App：`app/README.md`
