# Hermux

[![Build](https://github.com/Bahtya/hermux/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Bahtya/hermux/actions/workflows/build-apk.yml)
[![Tests](https://github.com/Bahtya/hermux/actions/workflows/run_tests.yml/badge.svg)](https://github.com/Bahtya/hermux/actions/workflows/run_tests.yml)
[![Release](https://github.com/Bahtya/hermux/actions/workflows/release.yml/badge.svg)](https://github.com/Bahtya/hermux/actions/workflows/release.yml)

> **Status: Work in Progress** — This project is under active development and not yet ready for production use.

Hermux 是一款 Android 终端模拟器，内置 [Hermes Agent](https://github.com/NousResearch/hermes-agent)，开箱即用。基于 [Termux](https://github.com/termux/termux-app) 二开，面向国内用户做了网络和体验定制。

## 它能做什么

Hermes Agent 是一个 AI 助手网关，将大语言模型连接到即时通讯平台。Hermux 将它打包进 Termux 终端，首次启动自动安装，无需手动配置 Linux 环境。

**支持的消息平台：**
- 飞书 / Lark（支持扫码建应用）
- Telegram
- Discord
- WhatsApp

**核心功能：**
- 首次启动自动安装 Hermes Agent（预构建 venv 解压，无需联网编译）
- Gateway 前台服务，支持开机自启和崩溃自动重启
- 内置配置管理（LLM 提供商、模型参数、API Key）
- Setup Wizard 引导配置流程

## 安装

从 [GitHub Releases](https://github.com/Bahtya/hermux/releases) 下载 APK。

- Android >= 7：下载 `apt-android-7` 变体
- Android 5/6：下载 `apt-android-5` 变体

也可从 [Nightly Build](https://github.com/Bahtya/hermux/actions/workflows/nightly-build.yml) 获取最新开发版（需登录 GitHub）。

> **注意：** 本项目与原版 Termux（`com.termux`）使用不同的包名（`com.hermux`）和签名，不可混装。

## 快速开始

1. 安装并打开 Hermux
2. 等待 Hermes Agent 自动安装完成（状态卡片会显示进度）
3. 点击工具栏 Hermes 图标，进入 Setup Wizard
4. 配置 LLM 提供商和 API Key
5. 选择消息平台并按指引完成接入
6. 启动 Gateway

## 架构

```
┌─────────────────────────────────────────┐
│           Hermux (App)          │
├──────────┬──────────┬───────────────────┤
│ Termux   │ Hermes   │ Hermes Gateway    │
│ Terminal │ Config   │ Service           │
│ Emulator │ Manager  │ (foreground,      │
│          │          │  auto-restart)     │
├──────────┴──────────┴───────────────────┤
│          Hermes Agent (CLI binary)       │
│    ~/.hermes/config.yaml · .env          │
├─────────────────────────────────────────┤
│   LD_PRELOAD path_rewrite.so             │
│   (com.termux → com.hermux runtime)      │
└─────────────────────────────────────────┘
```

**关键技术细节：**

| 组件 | 说明 |
|------|------|
| `scripts/patch-bootstrap.sh` | CI 阶段批量替换 bootstrap 中的 `com.termux` 路径 |
| `app/src/main/cpp/path_rewrite.c` | LD_PRELOAD 库，运行时拦截文件操作，重写编译期硬编码路径 |
| `HermesInstallHelper` | 安装逻辑：解压预构建 venv + 路径修正 + `hermes --help` 验证 |
| `HermesGatewayService` | 前台服务管理 Hermes Agent 进程，自动重启和崩溃保护 |
| `HermesConfigManager` | 管理 `~/.hermes/config.yaml` 和 `.env` 配置文件 |

## 构建

需要 Android SDK 和 NDK。

```bash
git clone https://github.com/Bahtya/hermux.git && cd hermux
bash scripts/patch-bootstrap.sh app/src/main/cpp   # 替换包名路径
./gradlew assembleRelease
```

CI 自动执行以上步骤。`downloadVenvs` task 自动从 GitHub 下载最新 venv。

## 发布流程

### hermes-agent 更新时

1. **构建 venv**（SSH 到 Termux ARM64 真机）

   前置条件：ADB 端口转发 `adb forward tcp:8022 tcp:8022`，Termux 设备已安装 `python rust make clang pkg-config libffi openssl ca-certificates git`。

   ```bash
   pip install paramiko
   python scripts/build-venv-device.py \
       --host 127.0.0.1 --port 8022 \
       --user <user> --password <password> \
       --hermes-commit <commit-hash>
   ```

2. **上传 venv 到 hermux releases**

   Tag 使用 hermes-agent 版本号：

   ```bash
   gh release create v0.13.0 venv-aarch64.tar.gz \
       --repo Bahtya/hermux --title "Venv v0.13.0"
   ```

3. **更新 `gradle.properties` 中的版本指向**

   ```properties
   hermesVenvVersion=v0.13.0
   ```

   提交并推送到 main。

4. **触发 APK 发布**

   ```bash
   gh workflow run release.yml --ref main --field tag=v1.3.44
   ```

   CI 自动：下载 venv → 构建 APK（android-7 + android-5）→ 创建 Release → 上传 APK。

### 仅发布 APK（无 venv 变更）

直接执行第 4 步即可。

## 上游项目

| 项目 | 仓库 | 说明 |
|------|------|------|
| Hermes Agent | [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent) | AI 助手网关，连接 LLM 与消息平台 |
| Termux | [termux/termux-app](https://github.com/termux/termux-app) | Android 终端模拟器和 Linux 环境 |

本仓库在 Termux 基础上：
- 包名从 `com.termux` 改为 `com.hermux`
- 内嵌 Hermes Agent 自动安装和管理
- 添加 Gateway 前台服务、配置管理、Setup Wizard 等
- 添加飞书扫码建应用流程

## License

本仓库包含 Termux 原始代码，遵循其原始许可证（见 [LICENSE](LICENSE) 和 [termux-shared/LICENSE.md](termux-shared/LICENSE.md)）。
