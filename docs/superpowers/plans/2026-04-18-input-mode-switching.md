# 输入模式切换实现计划

> **给执行 agent 的说明：** 必须使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务执行。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 添加三种输入模式（仅覆盖键盘、仅系统输入法、自动检测），支持物理键盘检测、系统输入法模式下的紧凑特殊键面板、设置页面 UI。

**架构概述：** 新增 `InputMode` 枚举存储在 SharedPreferences 中。新建 `KeyboardDetector` 类通过 `Configuration.hardKeyboardHidden` + `InputDevice` 扫描监测物理键盘连接。`MainActivity` 解析有效模式并控制 `SuppressableGeckoView.suppressIME`。系统输入法模式下，覆盖键盘 HTML 切换为紧凑布局，仅显示修饰键和特殊键。

**技术栈：** Kotlin (Android)、SharedPreferences、`Configuration`/`InputDevice` API、HTML/CSS/JS（覆盖键盘）

---

## 文件变更总览

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `app/src/main/java/com/vscodetunnel/app/KeyboardDetector.kt` | 物理键盘检测（Config + InputDevice 扫描） |
| 修改 | `app/src/main/java/com/vscodetunnel/app/AppSettings.kt` | 添加 `inputMode` 设置（overlay/ime/auto） |
| 修改 | `app/src/main/java/com/vscodetunnel/app/SuppressableGeckoView.kt` | 无需修改（suppressIME 开关已够用） |
| 修改 | `app/src/main/java/com/vscodetunnel/app/MainActivity.kt` | 接入 KeyboardDetector、解析有效模式、更新设置对话框、处理 onConfigurationChanged、Toast 通知 |
| 修改 | `app/src/main/java/com/vscodetunnel/app/OverlayManager.kt` | 添加紧凑模式 JS bridge 方法，处理紧凑模式显隐 |
| 修改 | `app/src/main/assets/overlay-ui/overlay.html` | 紧凑模式 CSS + JS、仅特殊键布局、展开/折叠切换 |

---

### 任务 1：在 AppSettings 中添加 InputMode 设置

**文件：**
- 修改：`app/src/main/java/com/vscodetunnel/app/AppSettings.kt`

**背景：** AppSettings 使用基于 SharedPreferences 的 Kotlin 扩展属性模式。每个设置有一个 KEY 常量和 `var Context.propertyName: Type` 的 getter/setter。参见第 6-142 行的完整模式。现有的 `suppressSystemKeyboard`（37-39 行）将被新的 `inputMode` 替换。

- [ ] **步骤 1：添加 InputMode 枚举和设置键**

在 `AppSettings.kt` 中，第 6 行（`object AppSettings {`）之后添加：

```kotlin
    enum class InputMode(val label: String) {
        OVERLAY("overlay"),
        SYSTEM_IME("ime"),
        AUTO("auto");

        companion object {
            fun fromString(s: String?): InputMode =
                entries.find { it.label == s } ?: OVERLAY
        }
    }
```

在第 31 行（`KEY_TP_INVERT_SCROLL`）之后添加键常量：

```kotlin
    private const val KEY_INPUT_MODE = "input_mode"
```

- [ ] **步骤 2：添加 inputMode 扩展属性**

替换现有的 `suppressSystemKeyboard` 属性（37-39 行）：

```kotlin
    // 删除以下 3 行：
    // var Context.suppressSystemKeyboard: Boolean
    //     get() = prefs(this).getBoolean(KEY_SUPPRESS_SYSKB, true)
    //     set(value) = prefs(this).edit().putBoolean(KEY_SUPPRESS_SYSKB, value).apply()

    // 替换为：
    var Context.inputMode: InputMode
        get() = InputMode.fromString(prefs(this).getString(KEY_INPUT_MODE, null))
        set(value) = prefs(this).edit().putString(KEY_INPUT_MODE, value.label).apply()
```

- [ ] **步骤 3：提交**

```bash
git add app/src/main/java/com/vscodetunnel/app/AppSettings.kt
git commit -m "feat: add InputMode enum and setting to AppSettings"
```

---

### 任务 2：创建 KeyboardDetector

**文件：**
- 新建：`app/src/main/java/com/vscodetunnel/app/KeyboardDetector.kt`

**背景：** Android 的 `Configuration.hardKeyboardHidden` 在物理键盘连接/断开时会变化。我们还扫描 `InputDevice` 列表查找键盘类型设备（source 包含 `SOURCE_KEYBOARD` 且不纯粹是 `SOURCE_TOUCHSCREEN`）。MainActivity 的 `onConfigurationChanged`（2588 行）已能接收配置变化，因为 manifest 在 `configChanges` 中声明了 `keyboard|keyboardHidden`。

- [ ] **步骤 1：创建 KeyboardDetector.kt**

创建 `app/src/main/java/com/vscodetunnel/app/KeyboardDetector.kt`：

```kotlin
package com.vscodetunnel.app

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice

/**
 * 检测物理键盘连接/断开。
 * 使用两个信号源：
 * 1. Configuration.hardKeyboardHidden — USB/BT 键盘连接时变化
 * 2. InputDevice 扫描 — 过滤 SOURCE_KEYBOARD 且有真实按键的设备，
 *    排除虚拟/仅触屏设备
 */
class KeyboardDetector(
    private val context: Context,
    private val onChanged: (connected: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "KeyboardDetector"
    }

    /** 上次检测时是否发现物理键盘 */
    var isPhysicalKeyboardConnected = false
        private set

    /** 从 onConfigurationChanged 调用 — 检查 Configuration.hardKeyboardHidden */
    fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val hidden = newConfig.hardKeyboardHidden
        val connected = hidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
        FileLogger.d(TAG, "onConfigurationChanged: hardKeyboardHidden=$hidden, connected=$connected")
        if (connected != isPhysicalKeyboardConnected) {
            isPhysicalKeyboardConnected = connected
            onChanged(connected)
        }
    }

    /** 扫描 InputDevice 列表查找真实键盘（排除触屏/鼠标） */
    fun scanAndNotify() {
        val connected = scanForKeyboards()
        FileLogger.d(TAG, "scanAndNotify: found=$connected, prev=${isPhysicalKeyboardConnected}")
        if (connected != isPhysicalKeyboardConnected) {
            isPhysicalKeyboardConnected = connected
            onChanged(connected)
        }
    }

    private fun scanForKeyboards(): Boolean {
        val ids = InputDevice.getDeviceIds()
        for (id in ids) {
            val device = InputDevice.getDevice(id) ?: continue
            val sources = device.sources
            val isKeyboard = sources and InputDevice.SOURCE_KEYBOARD != 0
            val isNotTouchscreen = sources and InputDevice.SOURCE_TOUCHSCREEN == 0
            val isNotMouse = sources and InputDevice.SOURCE_MOUSE == 0
            val hasKeys = device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE
            // 跳过虚拟键盘（名称通常包含 "virtual" 或 "uinput"）
            val name = device.name.lowercase()
            val isVirtual = name.contains("virtual") || name.contains("uinput")
            if (isKeyboard && isNotTouchscreen && isNotMouse && hasKeys && !isVirtual) {
                FileLogger.d(TAG, "Physical keyboard found: ${device.name}, sources=$sources, keyboardType=${device.keyboardType}")
                return true
            }
        }
        return false
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add app/src/main/java/com/vscodetunnel/app/KeyboardDetector.kt
git commit -m "feat: add KeyboardDetector for physical keyboard detection"
```

---

### 任务 3：在 MainActivity 中接入输入模式

**文件：**
- 修改：`app/src/main/java/com/vscodetunnel/app/MainActivity.kt`

**背景：** MainActivity（2757 行）是中央协调器。关键位置：
- 第 99 行：`sysKBSuppressed` 字段
- 256-258 行：onCreate 中的 IME 抑制设置
- 2588-2593 行：`onConfigurationChanged`（目前仅记录日志）
- 1893-1904 行：`onOverlayVisibilityChanged`
- 1997-2008 行：`showLauncher()` 重置 IME
- 第 54 行：`suppressSystemKeyboard` 导入
- 1642-1649 行：键盘设置的保存

旧的 `suppressSystemKeyboard` 布尔值映射到新的 `InputMode`：
- `suppressSystemKeyboard = true` → `InputMode.OVERLAY`
- `suppressSystemKeyboard = false` → `InputMode.SYSTEM_IME`

- [ ] **步骤 1：更新导入**

在 `MainActivity.kt` 中，替换第 54 行：

```kotlin
// 删除：
import com.vscodetunnel.app.AppSettings.suppressSystemKeyboard

// 替换为：
import com.vscodetunnel.app.AppSettings.inputMode
import com.vscodetunnel.app.AppSettings.InputMode
```

- [ ] **步骤 2：添加 KeyboardDetector 字段和有效模式辅助方法**

在第 99 行（`private var sysKBSuppressed = false`）之后添加：

```kotlin
    private lateinit var keyboardDetector: KeyboardDetector
```

在字段声明之后、`onCreate` 之前（约第 100 行）添加新的辅助方法：

```kotlin
    /** 根据设置和物理键盘状态解析有效输入模式 */
    private fun effectiveInputMode(): InputMode {
        if (inputMode == InputMode.AUTO) {
            return if (keyboardDetector.isPhysicalKeyboardConnected) InputMode.SYSTEM_IME else InputMode.OVERLAY
        }
        return inputMode
    }

    /** 将当前有效输入模式应用到 GeckoView 和覆盖层 */
    private fun applyInputMode() {
        val mode = effectiveInputMode()
        val suppress = when (mode) {
            InputMode.OVERLAY -> true
            InputMode.SYSTEM_IME -> false
        }
        sysKBSuppressed = suppress
        geckoView.suppressIME = suppress
        if (suppress) {
            val controller = WindowInsetsControllerCompat(window, geckoView)
            controller.hide(WindowInsetsCompat.Type.ime())
        }
        // 将紧凑模式推送到覆盖层（显示/隐藏字母键）
        overlayManager.setCompactMode(mode == InputMode.SYSTEM_IME)
        ViewCompat.requestApplyInsets(findViewById(R.id.rootFrame))
        FileLogger.d(TAG, "applyInputMode: mode=$mode, suppress=$suppress")
    }
```

- [ ] **步骤 3：在 onCreate 中初始化 KeyboardDetector**

在 `onCreate()` 中，替换 255-258 行：

```kotlin
        // 删除：
        // Apply suppress setting immediately so it's ready before any session
        if (suppressSystemKeyboard) {
            geckoView.suppressIME = true
            sysKBSuppressed = true
        }

        // 替换为：
        // 初始化键盘检测器并应用输入模式
        keyboardDetector = KeyboardDetector(this) { connected ->
            runOnUiThread {
                val mode = inputMode
                if (mode == InputMode.AUTO) {
                    if (connected) {
                        android.widget.Toast.makeText(this,
                            R.string.physical_keyboard_connected,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(this,
                            R.string.physical_keyboard_disconnected,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    applyInputMode()
                }
            }
        }
        keyboardDetector.scanAndNotify()
        applyInputMode()
```

- [ ] **步骤 4：更新 onConfigurationChanged**

替换 2588-2593 行：

```kotlin
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        FileLogger.d(TAG, "Configuration changed: orientation=${newConfig.orientation}, " +
            "screenWidthDp=${newConfig.screenWidthDp}, screenHeightDp=${newConfig.screenHeightDp}, " +
            "smallestScreenWidthDp=${newConfig.smallestScreenWidthDp}, densityDpi=${newConfig.densityDpi}")
        // 物理键盘检测
        keyboardDetector.onConfigurationChanged(newConfig)
    }
```

- [ ] **步骤 5：更新 onOverlayVisibilityChanged**

替换 `onOverlayVisibilityChanged`（1893-1904 行）：

```kotlin
    private fun onOverlayVisibilityChanged(visible: Boolean) {
        val mode = effectiveInputMode()
        // OVERLAY 模式：覆盖层可见时抑制
        // SYSTEM_IME 模式：永不抑制（让系统输入法工作）
        val suppress = when (mode) {
            InputMode.OVERLAY -> visible
            InputMode.SYSTEM_IME -> false
        }
        sysKBSuppressed = suppress
        geckoView.suppressIME = suppress
        FileLogger.d(TAG, "Overlay visible: $visible, mode: $mode, sysKB suppressed: $suppress")
        if (suppress) {
            val controller = WindowInsetsControllerCompat(window, geckoView)
            controller.hide(WindowInsetsCompat.Type.ime())
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.rootFrame))
    }
```

- [ ] **步骤 6：更新所有直接设置 suppressIME/suppressSystemKeyboard 的位置**

查找并更新 MainActivity 中所有剩余的 `suppressSystemKeyboard` 引用：

**第 211 行：** 将 `overlayManager.alwaysSuppressInput = suppressSystemKeyboard` 替换为：
```kotlin
        overlayManager.alwaysSuppressInput = (inputMode != InputMode.SYSTEM_IME)
```

**1642-1649 行（设置保存）：** 替换为：
```kotlin
            inputMode = inputModeFromRadio(selectedInputModeIndex)
            overlayManager.alwaysSuppressInput = (inputMode != InputMode.SYSTEM_IME)
            overlayManager.syncInputSuppression()
            applyInputMode()
```

**1831-1833、1849、2062-2064、2081 行：** 搜索 `suppressSystemKeyboard` 引用，将每处替换为 `inputMode != InputMode.SYSTEM_IME` 逻辑或 `applyInputMode()` 调用。

具体地，对于每个执行 `if (suppressSystemKeyboard) { geckoView.suppressIME = true; sysKBSuppressed = true }` 的位置，替换为 `applyInputMode()`。

- [ ] **步骤 7：提交**

```bash
git add app/src/main/java/com/vscodetunnel/app/MainActivity.kt
git commit -m "feat: wire up KeyboardDetector and InputMode in MainActivity"
```

---

### 任务 4：添加 Toast 字符串资源

**文件：**
- 修改：`app/src/main/res/values/strings.xml`

- [ ] **步骤 1：添加字符串资源**

找到 strings 资源文件并添加：

```xml
    <string name="physical_keyboard_connected">检测到物理键盘 — 已切换为系统输入</string>
    <string name="physical_keyboard_disconnected">物理键盘已移除 — 已恢复覆盖键盘</string>
```

- [ ] **步骤 2：提交**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat: add toast strings for physical keyboard events"
```

---

### 任务 5：在 OverlayManager 中添加紧凑模式

**文件：**
- 修改：`app/src/main/java/com/vscodetunnel/app/OverlayManager.kt`

**背景：** OverlayManager（602 行）控制覆盖层 WebView。它有 `sendToContentScript()` 用于向 GeckoView 内容脚本发送消息，可通过 `webView.evaluateJavascript()` 在覆盖层 HTML 中执行 JS。`JSInterface` 内部类（492 行）通过 `window.Android` 向覆盖层 JS 暴露方法。

- [ ] **步骤 1：添加紧凑模式状态和方法**

在 `alwaysSuppressInput`（47 行）之后添加：

```kotlin
    // 为 true 时，覆盖层仅显示特殊键行（系统输入法模式）
    private var compactMode = false
```

在 `syncInputSuppression()`（186 行）之后添加新的公共方法：

```kotlin
    /** 切换覆盖层紧凑模式（仅特殊键）或完整模式 */
    fun setCompactMode(enabled: Boolean) {
        if (compactMode == enabled) return
        compactMode = enabled
        webView.post {
            webView.evaluateJavascript(
                "if(typeof setCompactMode==='function')setCompactMode($enabled)", null
            )
        }
    }
```

- [ ] **步骤 2：在覆盖层显示时推送紧凑模式状态**

在 `show()` 方法（90 行）中，`applyInvertToOverlay(invertEnabled)`（106 行）之后添加：

```kotlin
        // 推送紧凑模式状态（可能在隐藏期间已改变）
        if (compactMode) {
            webView.post {
                webView.evaluateJavascript(
                    "if(typeof setCompactMode==='function')setCompactMode(true)", null
                )
            }
        }
```

- [ ] **步骤 3：在 JSInterface 中添加输入模式切换**

在 `JSInterface` 内部类中（`haptic()` 之后，约 600 行），添加：

```kotlin
        @JavascriptInterface
        fun toggleInputMode() {
            geckoView.post {
                // 循环切换：overlay → ime → overlay
                val ctx = geckoView.context
                val current = ctx.inputMode
                val next = when (current) {
                    InputMode.OVERLAY -> InputMode.SYSTEM_IME
                    InputMode.SYSTEM_IME -> InputMode.OVERLAY
                    InputMode.AUTO -> InputMode.OVERLAY
                }
                ctx.inputMode = next
                // Activity 通过 applyInputMode 拾取变更
                // 使用回调通知 Activity
                onInputModeToggled?.invoke()
            }
        }
```

在 OverlayManager 中添加新的回调属性（构造函数中 `onBackToMenu` 之后，约 20 行）：

```kotlin
    var onInputModeToggled: (() -> Unit)? = null
```

- [ ] **步骤 4：提交**

```bash
git add app/src/main/java/com/vscodetunnel/app/OverlayManager.kt
git commit -m "feat: add compact mode support to OverlayManager"
```

---

### 任务 6：在 overlay.html 中添加紧凑模式 UI

**文件：**
- 修改：`app/src/main/assets/overlay-ui/overlay.html`

**背景：** overlay.html（1595 行）是覆盖键盘 UI。关键结构：
- 232-240 行：工具栏 — Menu、Clip、Lang、TP、Invert、Hide 按钮
- 242-252 行：面板 — kbPanel（键盘）和 tpPanel（触摸板）
- 260-273 行：JS 状态变量（mods、tp、compactPage、fnActive、capsLock）
- 276-280 行：Android bridge `A = window.Android`
- 338 行+：LAYOUTS 对象定义键盘行

紧凑模式需要：
1. 隐藏所有字母键行
2. 仅显示特殊键行：Ctrl、Alt、Shift、Esc、←、→、↑、↓、Tab、Bksp
3. 添加展开按钮切换完整特殊键面板（F1-F12、Home/End 等）
4. 紧凑模式下 Ctrl/Alt/Shift 使用切换模式（点击激活，再次点击取消）

- [ ] **步骤 1：添加紧凑模式 CSS**

在现有 CSS 之后（228 行 `</style>` 之前）添加：

```css
/* ============ 紧凑模式（系统输入法） ============ */
html.compact .toolbar { gap: 4px; padding: 4px 8px; }
html.compact .panels .kb-panel { padding: 2px 3px 4px; }
html.compact .row-alpha,
html.compact .row-num,
html.compact .row-home,
html.compact .row-shift,
html.compact .row-bottom,
html.compact .action-bar,
html.compact .row-scroll { display: none !important; }
/* 紧凑特殊键行 */
html.compact .compact-row { display: flex !important; }
.compact-row { display: none; gap:3px; padding:2px 3px; justify-content:center; }
.compact-row .key { height:52px; font-size:20px; }
.compact-row .key-mod { flex:1.2 1 0; }
/* 展开的特殊键面板（Fn 键 + 导航键） */
html.compact .expanded-panel { display: flex !important; flex-wrap:wrap; gap:2px; padding:2px 3px; }
.expanded-panel { display:none; }
.expanded-panel .key { height:44px; font-size:16px; flex: 0 0 auto; min-width:40px; padding:0 6px; background:rgba(255,255,255,0.05); }
.expanded-panel .key:active { background:#1b1b1b; }
.expanded-panel .key.mod-active { background:#1b1b1b; color:#3399ff; }
```

- [ ] **步骤 2：添加紧凑模式 HTML 元素**

在工具栏 div（240 行）之后、面板 div（242 行）之前，添加紧凑模式行：

```html
<!-- 紧凑模式：仅特殊键（html 含 "compact" 类时显示） -->
<div class="compact-row" id="compactRow">
    <button class="key key-mod" data-mod="ctrl" id="compactCtrl">Ctrl</button>
    <button class="key key-mod" data-mod="alt" id="compactAlt">Alt</button>
    <button class="key key-mod" data-mod="shift" id="compactShift">Shift</button>
    <button class="key" data-key="Esc">Esc</button>
    <button class="key" data-key="Tab">Tab</button>
    <button class="key" data-key="Bksp">⌫</button>
    <button class="key" data-key="Left">←</button>
    <button class="key" data-key="Up">↑</button>
    <button class="key" data-key="Down">↓</button>
    <button class="key" data-key="Right">→</button>
    <button class="key" data-key="Enter">↵</button>
    <button class="tool-btn" id="compactExpand" style="padding:6px 10px;font-size:16px;">⤢</button>
</div>
<div class="expanded-panel" id="expandedPanel">
    <button class="key" data-key="F1">F1</button>
    <button class="key" data-key="F2">F2</button>
    <button class="key" data-key="F3">F3</button>
    <button class="key" data-key="F4">F4</button>
    <button class="key" data-key="F5">F5</button>
    <button class="key" data-key="F6">F6</button>
    <button class="key" data-key="F7">F7</button>
    <button class="key" data-key="F8">F8</button>
    <button class="key" data-key="F9">F9</button>
    <button class="key" data-key="F10">F10</button>
    <button class="key" data-key="F11">F11</button>
    <button class="key" data-key="F12">F12</button>
    <button class="key" data-key="Home">Home</button>
    <button class="key" data-key="End">End</button>
    <button class="key" data-key="PgUp">PgUp</button>
    <button class="key" data-key="PgDn">PgDn</button>
    <button class="key" data-key="Ins">Ins</button>
    <button class="key" data-key="Del">Del</button>
</div>
```

- [ ] **步骤 3：添加紧凑模式 JS 函数**

在 `<script>` 部分，`applyInvert()` 函数之后（约 1548 行）添加：

```javascript
// ======================== 紧凑模式 ========================
let compactExpanded = false;

function setCompactMode(enabled) {
    document.documentElement.classList.toggle('compact', enabled);
    if (!enabled) {
        compactExpanded = false;
        document.getElementById('expandedPanel').style.display = '';
    }
    requestAnimationFrame(() => notifyHeight());
}

// 紧凑行按键处理 — 修饰键使用切换模式（点击=激活，再次点击=取消）
document.getElementById('compactCtrl').addEventListener('pointerdown', e => {
    e.preventDefault(); haptic();
    const btn = e.target;
    const active = btn.classList.toggle('mod-active');
    mods.ctrl = active;
});
document.getElementById('compactAlt').addEventListener('pointerdown', e => {
    e.preventDefault(); haptic();
    const btn = e.target;
    const active = btn.classList.toggle('mod-active');
    mods.alt = active;
});
document.getElementById('compactShift').addEventListener('pointerdown', e => {
    e.preventDefault(); haptic();
    const btn = e.target;
    const active = btn.classList.toggle('mod-active');
    mods.shift = active;
});

// 紧凑行非修饰键 — 发送按键事件，自动取消修饰键激活状态
document.querySelectorAll('.compact-row .key:not(.key-mod)').forEach(btn => {
    btn.addEventListener('pointerdown', e => {
        e.preventDefault(); haptic();
        const key = btn.dataset.key;
        if (key) {
            A.sendKey(JSON.stringify({ key, ctrl:mods.ctrl, alt:mods.alt, shift:mods.shift }));
            // 发送按键后自动取消修饰键（模拟真实键盘行为）
            if (mods.ctrl) { mods.ctrl = false; document.getElementById('compactCtrl').classList.remove('mod-active'); }
            if (mods.alt) { mods.alt = false; document.getElementById('compactAlt').classList.remove('mod-active'); }
            if (mods.shift) { mods.shift = false; document.getElementById('compactShift').classList.remove('mod-active'); }
        }
    });
});

// 展开/折叠特殊键面板
document.getElementById('compactExpand').addEventListener('pointerdown', e => {
    e.preventDefault(); haptic();
    compactExpanded = !compactExpanded;
    const panel = document.getElementById('expandedPanel');
    panel.style.display = compactExpanded ? 'flex' : '';
    e.target.textContent = compactExpanded ? '⤡' : '⤢';
    requestAnimationFrame(() => notifyHeight());
});

// 展开面板按键处理 — 发送按键，自动取消修饰键
document.querySelectorAll('.expanded-panel .key').forEach(btn => {
    btn.addEventListener('pointerdown', e => {
        e.preventDefault(); haptic();
        const key = btn.dataset.key;
        if (key) {
            A.sendKey(JSON.stringify({ key, ctrl:mods.ctrl, alt:mods.alt, shift:mods.shift }));
            if (mods.ctrl) { mods.ctrl = false; document.getElementById('compactCtrl').classList.remove('mod-active'); }
            if (mods.alt) { mods.alt = false; document.getElementById('compactAlt').classList.remove('mod-active'); }
            if (mods.shift) { mods.shift = false; document.getElementById('compactShift').classList.remove('mod-active'); }
        }
    });
});
```

- [ ] **步骤 4：在工具栏中添加输入模式切换按钮**

在工具栏 HTML（232-240 行）中，hideBtn 之后添加新按钮：

```html
    <button class="tool-btn" id="inputModeBtn" title="切换输入模式">⌨</button>
```

在 JS 中添加点击处理器（hideBtn 处理器之后，约 1522 行）：

```javascript
// 输入模式切换 — 通知 Android 循环切换模式
document.getElementById('inputModeBtn').addEventListener('pointerdown', e => {
    e.preventDefault();
    if (typeof A.toggleInputMode === 'function') A.toggleInputMode();
});
```

- [ ] **步骤 5：提交**

```bash
git add app/src/main/assets/overlay-ui/overlay.html
git commit -m "feat: add compact mode UI to overlay keyboard"
```

---

### 任务 7：更新设置对话框 — 用 RadioGroup 替换 Checkbox

**文件：**
- 修改：`app/src/main/java/com/vscodetunnel/app/MainActivity.kt`

**背景：** 设置对话框（1402 行）使用 `section()` 辅助方法，然后添加 `check()`、`label()`、`field()` 辅助方法。键盘部分从 1538 行的 `section("Keyboard")` 开始。需要将 `suppressCheck` 复选框替换为输入模式的三选一单选组。

- [ ] **步骤 1：用 RadioGroup 替换 suppressCheck**

在 `showSettingsDialog()` 中，替换 1540-1541 行：

```kotlin
        // 删除：
        // val suppressCheck = check("Suppress system keyboard in sessions", suppressSystemKeyboard)
        // layout.addView(suppressCheck)

        // 替换为：
        label("输入模式")
        val inputModeLabels = arrayOf("仅内置键盘 (Overlay only)", "仅系统输入法 (System IME)", "自动检测 (Auto)")
        val inputModeValues = arrayOf(InputMode.OVERLAY, InputMode.SYSTEM_IME, InputMode.AUTO)
        val inputModeGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        var selectedInputModeIndex = inputModeValues.indexOf(inputMode).coerceAtLeast(0)
        inputModeLabels.forEachIndexed { idx, text ->
            android.widget.RadioButton(this).apply {
                this.text = text; isChecked = idx == selectedInputModeIndex
                setTextColor(colorPrim); textSize = 15f
                id = View.generateViewId()
                inputModeGroup.addView(this)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedInputModeIndex = idx
                }
            }
        }
        layout.addView(inputModeGroup)
```

- [ ] **步骤 2：添加 inputModeFromRadio 辅助方法**

在 MainActivity 中添加私有辅助方法：

```kotlin
    private fun inputModeFromRadio(index: Int): InputMode {
        return when (index) {
            0 -> InputMode.OVERLAY
            1 -> InputMode.SYSTEM_IME
            2 -> InputMode.AUTO
            else -> InputMode.OVERLAY
        }
    }
```

- [ ] **步骤 3：更新设置保存代码块**

已在任务 3 步骤 6 中覆盖。确保保存代码块从单选组读取 `selectedInputModeIndex`。

- [ ] **步骤 4：接入 OverlayManager 的 onInputModeToggled 回调**

在 `onCreate()` 中，`overlayManager.setup()`（210 行）之后添加：

```kotlin
        overlayManager.onInputModeToggled = { applyInputMode() }
```

- [ ] **步骤 5：提交**

```bash
git add app/src/main/java/com/vscodetunnel/app/MainActivity.kt
git commit -m "feat: replace suppress checkbox with input mode radio group in settings"
```

---

### 任务 8：系统输入法模式下 FloatingTouchpad 最小化

**文件：**
- 修改：`app/src/main/java/com/vscodetunnel/app/FloatingTouchpad.kt`

**背景：** FloatingTouchpad（350 行）是一个 FrameLayout，使用编程式 UI。在系统输入法模式下，触摸板应最小化为一个小的可拖动图标。点击后展开回完整触摸板大小。

- [ ] **步骤 1：添加最小化状态**

在现有字段之后（53 行），添加：

```kotlin
    private var minimized = false
    private var fullTouchpadWidth = 0
    private var fullTouchpadHeight = 0
```

- [ ] **步骤 2：添加 minimize/expand 方法**

在 `updateSize()`（100 行）之后添加：

```kotlin
    /** 最小化为小型浮动图标（用于系统输入法模式） */
    fun minimize() {
        if (minimized) return
        minimized = true
        fullTouchpadWidth = layoutParams?.width ?: width
        fullTouchpadHeight = layoutParams?.height ?: height
        // 隐藏除手柄栏外的所有子元素
        val root = getChildAt(0) as? LinearLayout ?: return
        for (i in 0 until root.childCount) {
            root.getChildAt(i).visibility = if (i == 0) View.VISIBLE else View.GONE
        }
        layoutParams = (layoutParams ?: LayoutParams(dp(48), dp(48))).apply {
            width = dp(48); height = dp(48)
        }
        background = GradientDrawable().apply {
            setColor(0xE6252526.toInt())
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), 0xFF404040.toInt())
        }
    }

    /** 从最小化图标展开回完整触摸板 */
    fun expand() {
        if (!minimized) return
        minimized = false
        val root = getChildAt(0) as? LinearLayout ?: return
        for (i in 0 until root.childCount) {
            root.getChildAt(i).visibility = View.VISIBLE
        }
        val w = if (fullTouchpadWidth > 0) fullTouchpadWidth else dp(200)
        val h = if (fullTouchpadHeight > 0) fullTouchpadHeight else dp(180)
        layoutParams = (layoutParams ?: LayoutParams(w, h)).apply {
            width = w; height = h
        }
        background = GradientDrawable().apply {
            setColor(0xE6252526.toInt())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), 0xFF404040.toInt())
        }
    }

    /** 触摸板是否处于最小化（图标）状态 */
    fun isMinimized() = minimized
```

- [ ] **步骤 3：使手柄栏点击在最小化模式下切换展开**

手柄栏的触摸监听器（141 行）处理拖动。需要添加点击展开逻辑。在手柄栏 `ACTION_UP` 处理器（154-156 行）中添加：

```kotlin
                MotionEvent.ACTION_UP -> {
                    savePosition()
                    // 最小化模式下，点击（短按，无拖动）展开
                    if (minimized) {
                        val dragDist = Math.abs(event.rawX - (this@FloatingTouchpad.translationX + dragOffsetX)) +
                                       Math.abs(event.rawY - (this@FloatingTouchpad.translationY + dragOffsetY))
                        if (dragDist < dp(10)) {
                            expand()
                            true
                        } else {
                            true
                        }
                    } else {
                        true
                    }
                }
```

- [ ] **步骤 4：提交**

```bash
git add app/src/main/java/com/vscodetunnel/app/FloatingTouchpad.kt
git commit -m "feat: add minimize/expand to FloatingTouchpad for system-IME mode"
```

---

### 任务 9：在 MainActivity 中接入 FloatingTouchpad 最小化

**文件：**
- 修改：`app/src/main/java/com/vscodetunnel/app/MainActivity.kt`

- [ ] **步骤 1：更新 applyInputMode 以处理触摸板**

在任务 3 中添加的 `applyInputMode()` 方法中，`overlayManager.setCompactMode(...)` 之后添加：

```kotlin
        // 根据模式最小化/恢复浮动触摸板
        if (mode == InputMode.SYSTEM_IME && floatingTouchpad.visibility == View.VISIBLE) {
            floatingTouchpad.minimize()
        } else if (mode == InputMode.OVERLAY && floatingTouchpad.isMinimized()) {
            floatingTouchpad.expand()
            floatingTouchpad.updateSize()
        }
```

- [ ] **步骤 2：提交**

```bash
git add app/src/main/java/com/vscodetunnel/app/MainActivity.kt
git commit -m "feat: wire FloatingTouchpad minimize into input mode switching"
```

---

### 任务 10：构建验证

**文件：**
- 所有已修改的文件

- [ ] **步骤 1：运行构建**

```bash
./gradlew assembleDebug
```

期望结果：BUILD SUCCESSFUL

- [ ] **步骤 2：修复编译错误**

常见注意事项：
- 缺少 `InputMode`、`KeyboardDetector`、`RadioGroup`、`RadioButton` 的导入
- 旧的 `suppressSystemKeyboard` 导入已删除但仍被引用
- `inputModeFromRadio()` 方法必须在使用前定义

- [ ] **步骤 3：如需修复则最终提交**

```bash
git add -A
git commit -m "fix: resolve compilation issues from input mode implementation"
```

---

## 自检清单

### 1. 规格覆盖

| 规格需求 | 对应任务 |
|---------|---------|
| 三种模式：overlay/ime/auto | 任务 1（InputMode 枚举）、任务 3（有效模式） |
| 设置页：三选一单选组 | 任务 7 |
| 浮动菜单：快速切换按钮 | 任务 6（工具栏中的 inputModeBtn） |
| 紧凑特殊键行 | 任务 6（CSS + HTML + JS） |
| 可展开的 Fn 键面板 | 任务 6（expanded-panel） |
| Ctrl/Alt/Shift 切换模式 | 任务 6（紧凑修饰键处理器） |
| 触摸板最小化为图标 | 任务 8 |
| 物理键盘检测 | 任务 2（KeyboardDetector） |
| 键盘事件 Toast 提示 | 任务 3（onChanged 回调中）、任务 4（字符串） |

### 2. 占位符扫描

未发现 TBD、TODO 或占位符模式。

### 3. 类型一致性

- `InputMode` 枚举在 AppSettings、MainActivity、OverlayManager 中一致使用
- `KeyboardDetector` 构造函数签名为 `(Context, (Boolean) -> Unit)` — 与任务 3 中的用法匹配
- OverlayManager 中的 `setCompactMode(Boolean)` 与 JS 的 `setCompactMode(enabled)` 调用匹配
- `onInputModeToggled` 回调属性已添加到 OverlayManager，在任务 7 的 MainActivity 中接入
- FloatingTouchpad 上的 `isMinimized()` 方法在任务 9 的 MainActivity 中调用

### 遗留兼容

- 旧设置迁移：`suppressSystemKeyboard = true` → `InputMode.OVERLAY`，`false` → `InputMode.SYSTEM_IME`。由于 `InputMode.OVERLAY` 是默认值（fromString 对 null 返回 OVERLAY），现有用户体验不变。
- AppSettings.kt 中的 `suppressSystemKeyboard` 导入必须删除。MainActivity 中所有调用者改为引用新的 `inputMode` 属性。
