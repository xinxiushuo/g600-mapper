package com.g600.mapper

import android.view.KeyEvent

/** 手柄按键表：Android 上 HID 手柄会被映射成这些 KeyCode */
object PadKeys {

    private val NAMES = linkedMapOf(
        KeyEvent.KEYCODE_BUTTON_A to "A",
        KeyEvent.KEYCODE_BUTTON_B to "B",
        KeyEvent.KEYCODE_BUTTON_X to "X",
        KeyEvent.KEYCODE_BUTTON_Y to "Y",
        KeyEvent.KEYCODE_BUTTON_L1 to "L1",
        KeyEvent.KEYCODE_BUTTON_R1 to "R1",
        KeyEvent.KEYCODE_BUTTON_L2 to "L2",
        KeyEvent.KEYCODE_BUTTON_R2 to "R2",
        KeyEvent.KEYCODE_BUTTON_START to "START",
        KeyEvent.KEYCODE_BUTTON_SELECT to "SELECT",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "L3",
        KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
        KeyEvent.KEYCODE_BUTTON_MODE to "MODE",
        KeyEvent.KEYCODE_DPAD_UP to "↑",
        KeyEvent.KEYCODE_DPAD_DOWN to "↓",
        KeyEvent.KEYCODE_DPAD_LEFT to "←",
        KeyEvent.KEYCODE_DPAD_RIGHT to "→",
        // 注意: 这个手柄的 BACK 键走 Consumer 通道(AC Back), 到 Android 上就是 KEYCODE_BACK。
        // 必须配合 MapperService 里的设备来源判断, 否则会吞掉手机自己的返回键。
        KeyEvent.KEYCODE_BACK to "BACK",
        // 感叹号键 = Consumer Help(0x0095)。蓝牙模式固件不发, 必须用 2.4G。
        // Android 是否把它翻译成 KEYCODE_HELP 取决于系统的 keylayout, 装上按一下就知道。
        KeyEvent.KEYCODE_HELP to "!",
    )

    val allCodes: List<Int> get() = NAMES.keys.toList()

    fun label(code: Int): String = NAMES[code] ?: "KEY_$code"

    fun isGamepadKey(code: Int): Boolean = NAMES.containsKey(code)

    /** 默认布局：给每个键一个初始位置（按屏幕比例） */
    fun defaultLayout(): List<MappingPoint> {
        val order = listOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_HELP
        )
        return order.map { code ->
            MappingPoint(code, label(code))
        }
    }
}
