package com.vscodetunnel.app

import android.content.Context
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
