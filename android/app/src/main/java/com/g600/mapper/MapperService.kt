package com.g600.mapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 核心服务：
 *  1. onKeyEvent  ---- 全局接收手柄按键（即使本 APP 不在前台）
 *  2. dispatchGesture - 在映射点注入触摸
 *
 * 注入策略说明：
 *  dispatchGesture 在同一时刻只允许一个手势在执行，新手势会取消旧手势。
 *  所以这里用「周期性重发」的方式模拟"按住"：每 40ms 把当前所有按下的映射点
 *  合并成一个多指手势发出去。代价是游戏侧感知为高频点击而非严格的长按，
 *  但在非 root 环境下这是唯一既不误触又能多指并发的做法。
 */
class MapperService : AccessibilityService(), OverlayManager.OnLayoutChanged {

    companion object {
        @Volatile
        var instance: MapperService? = null
            private set
    }

    private lateinit var overlay: OverlayManager
    private val handler = Handler(Looper.getMainLooper())
    private val pressed = mutableSetOf<Int>()
    private var ticking = false

    /** 总开关：关掉后按键直接放行给系统。默认关，避免服务一开就吞掉手柄按键 */
    var mappingEnabled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayManager(this)

        // TYPE_ACCESSIBILITY_OVERLAY 是无障碍服务的特权窗口类型, 正常不需要用户单独授权;
        // 少数定制 ROM 会拦, show() 内部已 try/catch, 失败不会崩
        overlay.show(MappingStore.load(this), false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** 判断按键是否真的来自手柄设备（而不是手机自身按键/软键盘） */
    private fun isFromPad(event: KeyEvent): Boolean {
        val dev = event.device ?: return false
        val src = dev.sources
        return (src and InputDevice.SOURCE_JOYSTICK) != 0 ||
                (src and InputDevice.SOURCE_GAMEPAD) != 0
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!mappingEnabled) return false
        val code = event.keyCode
        if (!PadKeys.isGamepadKey(code)) return false

        // KEYCODE_BACK 和手机自己的返回键撞码, 必须按来源区分:
        // 手柄发来的才拦, 否则用户会把任何界面的返回键都按不出去
        if (code == KeyEvent.KEYCODE_BACK && !isFromPad(event)) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (pressed.add(code)) {
                    overlay.highlight(code, true)
                    ensureTicking()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (pressed.remove(code)) overlay.highlight(code, false)
            }
        }
        return true   // 吞掉，避免游戏和映射同时响应
    }

    private val tick = object : Runnable {
        override fun run() {
            if (pressed.isEmpty()) {
                ticking = false
                return
            }
            injectAll()
            handler.postDelayed(this, 40)
        }
    }

    private fun ensureTicking() {
        if (!ticking) {
            ticking = true
            handler.post(tick)
        }
    }

    private fun injectAll() {
        val pxs = mutableListOf<Pair<Float, Float>>()
        for (code in pressed) {
            val p = overlay.findPoint(code) ?: continue
            val xy = overlay.pixelOf(p) ?: continue
            pxs.add(xy)
        }
        if (pxs.isEmpty()) return
        val builder = GestureDescription.Builder()
        pxs.forEach { (x, y) ->
            val path = Path().apply { moveTo(x, y) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        }
        try {
            dispatchGesture(builder.build(), null, null)
        } catch (_: Exception) {
        }
    }

    // ---------------- 供界面调用 ----------------

    /** 切换编辑/锁定。show() 内部会重建悬浮层，flags 随之更新 */
    fun setEditing(edit: Boolean) {
        pressed.clear()
        overlay.show(MappingStore.load(this), edit)
    }

    fun reloadOverlay() {
        pressed.clear()
        overlay.show(MappingStore.load(this), overlay.isEditing)
    }

    fun isOverlayShowing(): Boolean = overlay.isShowing

    fun isEditing(): Boolean = overlay.isEditing

    override fun onLayoutChanged() {
        MappingStore.save(this, overlay.currentPoints())
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }
}
