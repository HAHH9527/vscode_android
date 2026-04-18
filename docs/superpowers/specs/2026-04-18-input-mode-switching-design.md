# 输入模式切换设计

**日期**: 2026-04-18
**状态**: Draft
**方案**: A — GeckoView 原生 IME + 紧凑特殊键面板

## 1. 背景

当前应用使用自定义覆盖键盘（HTML/JS，`overlay.html`）作为唯一输入方式，通过 `SuppressableGeckoView` 完全压制系统输入法。这导致：

- 用户无法使用系统输入法（如搜狗、Gboard）输入中文等语言
- 物理键盘连接后无法自动切换，体验不连贯
- 没有在内置键盘和系统输入法之间切换的选项

GeckoView 自带完善的 IME 支持（`SessionTextInput` → `GeckoInputConnection`），当前被 `SuppressableGeckoView` 压制。只需在系统输入法模式下解除压制，即可直接接收系统输入法事件。

## 2. 目标

1. 增加在内置覆盖键盘和系统输入法之间切换的功能
2. 检测物理键盘连接时自动切换到系统输入法 + 物理键盘模式
3. 系统输入法模式下提供最小化特殊键面板（Ctrl/Alt/Shift/Esc/方向键等）
4. 设置页和悬浮菜单提供切换入口

## 3. InputMode 状态模型

### 3.1 模式定义

```
enum InputMode {
    OVERLAY_ONLY,     // 仅内置覆盖键盘（当前默认行为）
    SYSTEM_IME,       // 仅系统输入法
    AUTO              // 自动：物理键盘在 → SYSTEM_IME，不在 → OVERLAY_ONLY
}
```

### 3.2 状态解析

优先级从高到低：

1. **悬浮菜单手动切换** → 立即生效（临时覆盖），持续到本次会话结束或用户再次切换
2. **设置页选择模式** → 持久化到 `AppSettings`，下次启动生效
3. **AUTO 模式物理键盘事件** → 在 OVERLAY_ONLY 和 SYSTEM_IME 间自动切换

### 3.3 存储

| 字段 | 类型 | 持久化 | 说明 |
|------|------|--------|------|
| `inputMode` | String ("overlay_only" / "system_ime" / "auto") | 是 | 设置页选择的模式 |
| `effectiveInputMode` | InputMode (运行时) | 否 | 当前实际生效的模式（考虑物理键盘状态） |
| `isPhysicalKeyboardConnected` | Boolean (运行时) | 否 | 物理键盘是否连接 |

### 3.4 模式转换

```
设置 "overlay_only" → 始终覆盖键盘，系统 IME 压制
设置 "system_ime"   → 始终系统输入法，覆盖键盘最小化
设置 "auto"         → 监听物理键盘 → 在两者间自动切换
悬浮菜单切换         → 临时覆盖当前模式（不修改设置页值）
```

### 3.5 有效模式计算

```kotlin
fun resolveEffectiveMode(
    setting: InputMode,
    manualOverride: InputMode?,
    physicalKeyboardConnected: Boolean
): InputMode {
    // 悬浮菜单临时覆盖优先
    if (manualOverride != null) return manualOverride

    return when (setting) {
        OVERLAY_ONLY -> OVERLAY_ONLY
        SYSTEM_IME -> SYSTEM_IME
        AUTO -> if (physicalKeyboardConnected) SYSTEM_IME else OVERLAY_ONLY
    }
}
```

## 4. 物理键盘检测

### 4.1 检测方式

- **主要**：`onConfigurationChanged` — 监听 `Configuration.hardKeyboardHidden` 变化
  - `HARDKEYBOARDHIDDEN_NO` = 物理键盘已连接
  - `HARDKEYBOARDHIDDEN_YES` = 物理键盘不可用
- **辅助**：`InputDevice.getDeviceIds()` 扫描 — 在 `onResume` 时主动检查初始状态

### 4.2 实现位置

- **新建** `KeyboardDetector.kt` — 封装检测逻辑
  - `isPhysicalKeyboardConnected(): Boolean`
  - 回调接口 `OnKeyboardStateChangedListener`
- `MainActivity.onConfigurationChanged` — 委托给 `KeyboardDetector`
- `MainActivity.onResume` — 初始状态检查

### 4.3 回调流程

```
KeyboardDetector 检测到物理键盘状态变化
  → OverlayManager.onPhysicalKeyboardChanged(connected: Boolean)
  → OverlayManager 计算有效模式
  → 切换 UI：覆盖键盘最小化/恢复、特殊键面板显隐、IME 压制/解除
```

### 4.4 KeyboardDetector 接口

```kotlin
class KeyboardDetector(private val context: Context) {
    interface OnKeyboardStateChangedListener {
        fun onPhysicalKeyboardChanged(connected: Boolean)
    }

    fun isPhysicalKeyboardConnected(): Boolean
    fun onConfigurationChanged(newConfig: Configuration)
    fun checkInitialState()
    fun setListener(listener: OnKeyboardStateChangedListener)
}
```

## 5. SuppressableGeckoView 改造

### 5.1 当前行为

`suppressIME = true` 时返回 null InputConnection，完全禁用系统输入法。

### 5.2 改造

`suppressIME` 由 `OverlayManager` 根据有效模式动态设置：

| 有效模式 | suppressIME | 覆盖键盘 | 特殊键面板 | 系统输入法 |
|----------|-------------|----------|------------|------------|
| OVERLAY_ONLY | true | 完整显示 | 不需要 | 压制 |
| SYSTEM_IME | false | 隐藏 | 最小化显示 | 可用 |
| AUTO + 物理键盘 | false | 隐藏 | 最小化显示 | 可用 |
| AUTO + 无物理键盘 | true | 完整显示 | 不需要 | 压制 |

## 6. 紧凑特殊键面板

### 6.1 最小化布局

系统输入法模式下，覆盖键盘缩小为单行特殊键：

```
┌──────────────────────────────────────────┐
│ [Ctrl] [Alt] [Shift] [Esc] [←] [↑] [↓] [→] [展开] │
└──────────────────────────────────────────┘
```

### 6.2 展开面板

点击展开按钮后显示完整特殊键面板：

```
┌──────────────────────────────────────────────┐
│ [Ctrl] [Alt] [Shift] [Esc] [Tab] [↵]        │
│ [F1] [F2] [F3] [F4] [F5] [F6] [F7] [F8]    │
│ [F9] [F10] [F11] [F12]                      │
│ [Home] [End] [PgUp] [PgDn] [Ins] [Del]      │
│ [←] [↑] [↓] [→]                  [收起]     │
└──────────────────────────────────────────────┘
```

### 6.3 实现方式

- 在 `overlay.html` 中增加 `compact-mode` CSS 类
- 最小化时隐藏所有字母/数字行，仅显示特殊键行
- 通过 JS bridge 调用 `Android.setCompactMode(true/false)` 控制状态

### 6.4 Ctrl/Alt/Shift 触发模式

- 点击 → 进入按住状态（按键高亮）
- 再点 → 释放状态
- 发送下一个按键时自动组合修饰符
- 此行为已在当前 `overlay.html` 中实现，复用即可

### 6.5 触摸板

- 系统输入法模式下默认**最小化**为一个小图标
- 点击展开为完整触摸板
- `FloatingTouchpad` 增加最小化状态 UI

## 7. SSH 终端的 IME 兼容

### 7.1 VS Code 模式

- GeckoView 原生 IME 管道直接可用，无需额外处理
- 系统输入法输入中文等 → GeckoView → VS Code → 正常工作

### 7.2 SSH 终端模式

- xterm.js WebView 有独立的输入路径，不受 `SuppressableGeckoView` 影响
- xterm.js 本身支持 `textarea` 捕获 IME 输入
- 物理键盘模式下，`KeyEvent` 通过 WebView → xterm.js → 终端，走现有路径
- 需确保终端 WebView 在 SYSTEM_IME 模式下不被压制

### 7.3 处理策略

- 当 InputTarget 为 `SSH_TERMINAL` 时，终端 WebView 的 IME 由终端 WebView 自身管理
- `SuppressableGeckoView` 的压制仅影响 GeckoView，不影响终端 WebView

## 8. UI 集成

### 8.1 悬浮菜单切换开关

- 在现有悬浮菜单中增加输入模式切换按钮
- 使用图标：内置键盘图标 ↔ 系统输入法图标
- 点击循环切换：OVERLAY_ONLY → SYSTEM_IME → AUTO → OVERLAY_ONLY
- 仅在会话激活时显示
- 长按可快速跳转到设置页

### 8.2 设置页

新增"输入模式"设置项：

```
输入模式：
  ○ 仅内置键盘
  ○ 仅系统输入法
  ● 自动（推荐）
```

- 修改此项立即生效
- "自动"选项说明文字："检测到物理键盘时自动切换到系统输入法"

### 8.3 状态提示

物理键盘插拔时显示 Toast：

- 连接：`"检测到物理键盘，已切换到系统输入法模式"`
- 断开：`"物理键盘已断开，已恢复内置键盘"`

## 9. 改动清单

| 文件 | 改动类型 | 改动说明 |
|------|----------|----------|
| `AppSettings.kt` | 修改 | 新增 `inputMode` 字段，运行时状态字段 |
| `SuppressableGeckoView.kt` | 修改 | `suppressIME` 由外部动态控制（语义不变，调用方变更） |
| `OverlayManager.kt` | 修改 | 增加 InputMode 状态机、模式切换逻辑、特殊键面板控制 |
| `FloatingTouchpad.kt` | 修改 | 最小化/展开状态支持 |
| `MainActivity.kt` | 修改 | `onConfigurationChanged`、`onResume` 集成 KeyboardDetector |
| `KeyboardDetector.kt` | **新建** | 物理键盘检测组件 |
| `overlay.html` | 修改 | compact-mode CSS、特殊键行布局、setCompactMode bridge |
| 设置页布局 | 修改 | 输入模式三选一 RadioGroup |
| 悬浮菜单 | 修改 | 切换按钮 |
| `AndroidManifest.xml` | 无需改动 | 已声明 `keyboard\|keyboardHidden` |

## 10. 风险和缓解

| 风险 | 缓解措施 |
|------|----------|
| SSH 终端 xterm.js IME 兼容性不确定 | 先验证 xterm.js IME 输入，如不兼容则仅对 VS Code 模式启用系统输入法 |
| 物理键盘检测可能有延迟 | `onResume` 主动检查 + `onConfigurationChanged` 被动监听双保险 |
| 特殊键面板在物理键盘模式下可能冗余 | 保留但最小化，用户按需展开 |
| 悬浮菜单临时覆盖可能导致混淆 | Toast 提示 + 悬浮菜单图标始终反映当前有效模式 |
