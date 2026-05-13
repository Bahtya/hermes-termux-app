# Bahtya Terminal

[![Build](https://github.com/Bahtya/hermes-termux-app/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Bahtya/hermes-termux-app/actions/workflows/build-apk.yml)
[![Tests](https://github.com/Bahtya/hermes-termux-app/actions/workflows/run_tests.yml/badge.svg)](https://github.com/Bahtya/hermes-termux-app/actions/workflows/run_tests.yml)
[![Release](https://github.com/Bahtya/hermes-termux-app/actions/workflows/release.yml/badge.svg)](https://github.com/Bahtya/hermes-termux-app/actions/workflows/release.yml)

> **Status: Work in Progress** — This project is under active development and not yet ready for production use.

Bahtya Terminal 是一款 Android 终端模拟器，内置 [Hermes Agent](https://github.com/NousResearch/hermes-agent)，开箱即用。基于 [Termux](https://github.com/termux/termux-app) 二开，面向国内用户做了网络和体验定制。

## 它能做什么

Hermes Agent 是一个 AI 助手网关，将大语言模型连接到即时通讯平台。Bahtya Terminal 将它打包进 Termux 终端，首次启动自动安装，无需手动配置 Linux 环境。

**支持的消息平台：**
- 飞书 / Lark（支持扫码建应用）
- Telegram
- Discord
- WhatsApp

**核心功能：**
- 首次启动自动安装 Hermes Agent，带进度指示和错误详情
- Gateway 前台服务，支持开机自启和崩溃自动重启
- 内置配置管理（LLM 提供商、模型参数、API Key）
- Setup Wizard 引导配置流程
- 国内 GitHub 镜像加速，安装失败自动 fallback

## 安装

从 [GitHub Releases](https://github.com/Bahtya/hermes-termux-app/releases) 下载 APK。

- Android >= 7：下载 `apt-android-7` 变体
- Android 5/6：下载 `apt-android-5` 变体

也可从 [Nightly Build](https://github.com/Bahtya/hermes-termux-app/actions/workflows/nightly-build.yml) 获取最新开发版（需登录 GitHub）。

> **注意：** 本项目与原版 Termux（`com.termux`）使用不同的包名（`com.bahtya`）和签名，不可混装。

## 快速开始

1. 安装并打开 Bahtya Terminal
2. 等待 Hermes Agent 自动安装完成（状态卡片会显示进度）
3. 点击工具栏 Hermes 图标，进入 Setup Wizard
4. 配置 LLM 提供商和 API Key
5. 选择消息平台并按指引完成接入
6. 启动 Gateway

## 架构

```
┌─────────────────────────────────────────┐
│           Bahtya Terminal (App)          │
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
│   (com.termux → com.bahtya runtime)      │
└─────────────────────────────────────────┘
```

**关键技术细节：**

| 组件 | 说明 |
|------|------|
| `scripts/patch-bootstrap.sh` | CI 阶段批量替换 bootstrap 中的 `com.termux` 路径 |
| `app/src/main/cpp/path_rewrite.c` | LD_PRELOAD 库，运行时拦截文件操作，重写编译期硬编码路径 |
| `HermesInstallHelper` | 安装逻辑，支持直连和 GitHub 镜像 fallback |
| `HermesGatewayService` | 前台服务管理 Hermes Agent 进程，自动重启和崩溃保护 |
| `HermesConfigManager` | 管理 `~/.hermes/config.yaml` 和 `.env` 配置文件 |

## 构建

```bash
# 1. 克隆仓库
git clone https://github.com/Bahtya/hermes-termux-app.git
cd hermes-termux-app

# 2. 补丁 bootstrap（替换包名路径）
bash scripts/patch-bootstrap.sh app/src/main/cpp

# 3. 构建 APK
./gradlew assembleRelease
```

构建需要 Android SDK 和 NDK。CI 会自动执行 bootstrap patching，本地构建需手动运行。

## 上游项目

| 项目 | 仓库 | 说明 |
|------|------|------|
| Hermes Agent | [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent) | AI 助手网关，连接 LLM 与消息平台 |
| Termux | [termux/termux-app](https://github.com/termux/termux-app) | Android 终端模拟器和 Linux 环境 |

本仓库在 Termux 基础上：
- 包名从 `com.termux` 改为 `com.bahtya`
- 内嵌 Hermes Agent 自动安装和管理
- 添加 Gateway 前台服务、配置管理、Setup Wizard 等
- 针对国内网络环境添加 GitHub 镜像加速
- 添加飞书扫码建应用流程

## License

本仓库包含 Termux 原始代码，遵循其原始许可证（见 [LICENSE](LICENSE) 和 [termux-shared/LICENSE.md](termux-shared/LICENSE.md)）。
