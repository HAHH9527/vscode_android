# 项目知识库

**生成时间:** 2026-04-17
**提交:** d807e8e
**分支:** main

## 概述

Android原生客户端，通过GeckoView嵌入VS Code Remote Tunnels。集成SSH（JSch）、Mosh（UDP终端）、SFTP文件浏览器、自定义覆盖键盘（xterm.js）。单模块Gradle项目，Kotlin + JNI（PTY原生桥接）。

## 结构

```
vscode_android/
├── app/src/main/
│   ├── java/com/vscodetunnel/app/   # 18个Kotlin源文件（所有业务逻辑）
│   ├── cpp/                          # JNI: pty_helper.c + CMake
│   ├── assets/                       # Web资产: 覆盖键盘UI、终端、SFTP浏览器
│   │   ├── overlay-ui/               #   自定义键盘HTML/JS/CSS
│   │   ├── overlay-extension/        #   GeckoView扩展
│   │   ├── terminal/                 #   xterm.js终端UI
│   │   ├── sftp/                     #   SFTP文件浏览器UI
│   │   └── terminfo/                 #   终端信息数据库
│   └── res/                          # 布局、drawable、values、xml
├── .github/workflows/build-android.yml  # CI: 构建、版本号、签名
├── build-mosh.sh                     # Mosh客户端交叉编译脚本
├── docs/                             # 功能文档（keyboard.md, ssh.md, sftp.md, settings.md）
├── build.gradle.kts                  # 根: AGP 8.9.1, Kotlin 2.3.10
├── app/build.gradle.kts              # 模块: compileSdk 36, NDK, GeckoView依赖
└── settings.gradle.kts               # 单模块: include(":app")
```

## 关键文件定位

| 需求 | 位置 | 说明 |
|------|------|------|
| VS Code隧道连接 | `TunnelApi.kt` | GitHub OAuth设备流程 + 隧道API |
| SSH连接 | `SshSessionManager.kt` | JSch SSH会话管理 |
| Mosh连接 | `MoshSessionManager.kt` | UDP终端（需mosh-client二进制） |
| VS Code渲染 | `GeckoManager.kt` + `SuppressableGeckoView.kt` | GeckoView加载和交互 |
| 覆盖键盘 | `OverlayManager.kt` + `FloatingTouchpad.kt` | 悬浮窗键盘和触摸板 |
| SFTP文件浏览 | `SftpManager.kt` | JSch SFTP通道操作 |
| 原生PTY | `PtyProcess.kt` + `cpp/pty_helper.c` | JNI桥接，fork/exec PTY |
| 会话保活 | `KeepAliveService.kt` | 前台服务，通知栏常驻 |
| 用户设置 | `AppSettings.kt` | SharedPreferences封装 |
| GitHub认证 | `GitHubAuth.kt` | OAuth设备码流程 |
| Cloudflare代理 | `CloudflareProxy.kt` | Cloudflare Tunnel代理支持 |
| Tmux管理 | `TmuxManager.kt` | Tmux会话管理 |
| 服务器存储 | `ServerStorage.kt` | 服务器信息持久化 |
| 日志 | `FileLogger.kt` | 文件日志系统 |
| 应用初始化 | `App.kt` | Application类，FileLogger初始化 |
| CI/构建 | `.github/workflows/build-android.yml` | 自动版本号、签名、mosh交叉编译 |

## 代码图

| 符号 | 类型 | 文件 | 角色 |
|------|------|------|------|
| `App` | class | App.kt | Application子类，初始化FileLogger |
| `MainActivity` | class | MainActivity.kt | 主Activity，协调所有组件 |
| `GeckoManager` | class | GeckoManager.kt | GeckoView生命周期和页面加载 |
| `TunnelApi` | object | TunnelApi.kt | VS Code隧道REST API客户端 |
| `SshSessionManager` | class | SshSessionManager.kt | SSH连接池和会话管理 |
| `MoshSessionManager` | class | MoshSessionManager.kt | Mosh UDP会话管理 |
| `SftpManager` | class | SftpManager.kt | SFTP文件操作 |
| `OverlayManager` | class | OverlayManager.kt | 悬浮窗覆盖层管理 |
| `FloatingTouchpad` | class | FloatingTouchpad.kt | 悬浮触摸板输入 |
| `PtyProcess` | class | PtyProcess.kt | JNI原生PTY进程 |
| `KeepAliveService` | class | KeepAliveService.kt | 前台保活服务 |
| `GitHubAuth` | object | GitHubAuth.kt | GitHub OAuth认证 |
| `CloudflareProxy` | class | CloudflareProxy.kt | Cloudflare Tunnel代理 |
| `TmuxManager` | object | TmuxManager.kt | Tmux会话操作 |
| `ServerStorage` | object | ServerStorage.kt | 服务器数据持久化 |
| `AppSettings` | object | AppSettings.kt | 用户偏好设置 |
| `FileLogger` | object | FileLogger.kt | 文件日志工具 |
| `SuppressableGeckoView` | class | SuppressableGeckoView.kt | 可抑制输入的GeckoView |

## 项目约定

- **纯Kotlin**：无Java源文件，所有业务逻辑在Kotlin中
- **Kotlin DSL**：`.gradle.kts` 而非 `.gradle`
- **扁平源码结构**：18个Kotlin文件全部在同一个包目录下，无子包
- **NDK仅arm64-v8a**：只构建64位ARM ABI，`-DANDROID_STL=none`（最小化原生库）
- **无测试**：项目中没有任何测试文件或测试配置
- **无代码检查工具**：无Proguard、lint、detekt、ktlint配置
- **Web资产嵌入**：覆盖键盘和终端通过assets中的HTML/JS/CSS实现，在GeckoView中渲染
- **Mozilla Maven仓库**：`https://maven.mozilla.org/maven2/` 用于GeckoView依赖

## 反模式（本项目禁忌）

- 不要添加Java源文件——项目是纯Kotlin
- 不要修改ABI过滤器——仅支持arm64-v8a
- 不要添加Proguard规则——项目未启用混淆
- 构建mosh-client需要在Linux环境交叉编译（非Windows直接构建）
- 覆盖键盘UI是Web资产（HTML/JS），不是原生Android View

## 构建命令

```bash
# 构建Debug APK
./gradlew assembleDebug

# 构建Release APK（需要签名配置）
./gradlew assembleRelease

# 清理构建
./gradlew clean

# 交叉编译mosh-client（需Linux环境）
./build-mosh.sh
```

## 注意事项

- CI自动从git tag提取版本号并修改`app/build.gradle.kts`中的`versionCode`/`versionName`
- GeckoView版本需要与 Mozilla Maven 仓库中的可用版本匹配
- Mosh功能需要预编译的mosh-client二进制文件（通过`build-mosh.sh`生成）
- `PtyProcess`通过JNI调用`pty_helper.c`中的原生方法，修改时需同时考虑Kotlin和C层
- 项目包名：`com.vscodetunnel.app`
