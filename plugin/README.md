# Vimalinx Server 插件

中文 | [English](README.en.md)

该插件用于连接 Gateway 与 Vimalinx Server，支持轮询（poll）或 webhook 入站模式，并使用 Token 认证。

## 需求

- Gateway 运行中（Node 22+）。
- 可访问的 Vimalinx Server。

## 安装

最简单（客户）：克隆仓库后在根目录运行：

```bash
./install.sh
```

## 配置（向导）

```bash
clawdbot onboard
```

向导会要求：
- 选择服务器（官方 / 自定义 URL）。
- 输入 Vimagram 生成的 Token。
- 选择入站模式（poll 或 webhook）。

## 配置（手动）

最小配置（单账号）：

```yaml
channels:
  vimalinx:
    baseUrl: http://server-host:8788
    userId: user-id
    token: host-token
    inboundMode: poll
```

可选字段：
- `webhookPath`（默认 `/vimalinx-webhook`）
- `webhookToken`（默认等于 `token`）

## 验证

```bash
clawdbot channels status --probe
```

若一切正常，状态会显示 connected/polling。

## 相关

- Server：`server/README.md`
- Android App：`app/README.md`
