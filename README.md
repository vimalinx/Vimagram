# Vimalinx Suite

中文 | [English](README.en.md)

Vimalinx Suite 是 Vimalinx Server 通道的全栈实现：
- Server：`server`
- Plugin：`plugin`
- Android App（Vimagram）：`app`

## 快速启动（本地）

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

安装并配置插件（已克隆仓库一键安装）：

```bash
./install.sh
```

脚本会提示输入 Server URL 与 Token，并自动写入配置（无需再跑 onboard）。
默认会执行：doctor --fix、gateway start、channels status --probe。
如需跳过，可设置：VIMALINX_SKIP_DOCTOR_FIX=1 / VIMALINX_SKIP_GATEWAY_START=1 / VIMALINX_SKIP_STATUS=1。
如需覆盖已安装插件，可设置：`VIMALINX_FORCE_OVERWRITE=1 ./install.sh`。

或手动安装并配置插件：

```bash
clawdbot plugins install ./plugin
clawdbot onboard
```

## 插件离线发布（给客户）

打包离线安装包：

```bash
./plugin/scripts/pack-release.sh
```

客户安装：

```bash
tar -xzf vimalinx-server-plugin-*.tgz
./package/scripts/install.sh
```

验证：

```bash
clawdbot channels status --probe
```

## Android App（Vimagram）

```bash
cd app
./gradlew :app:installDebug
```

登录服务器后，在 **Account** 生成主机 Token，并在插件向导里粘贴。

## 使用说明

- Server：`server/README.md`
- Plugin：`plugin/README.md`
- Android App：`app/README.md`
