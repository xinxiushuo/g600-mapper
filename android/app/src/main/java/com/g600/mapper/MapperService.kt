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
 *  1. onKeyEvent       —— 全局接收手柄按键（即使本 APP 不在前台）
 *  2. dispatchGesture  —— 在映射点注入触摸
 *
 * 只拦截**已经绑定到某个圆点**的按键，其余一律放行给系统。
 * 这样即使系统把某个键翻译成了 KEYCODE_BACK 之类的系统键，只要用户没绑定它，
 * 就不会被吞掉（旧版按"手柄键表"整片拦截，把手机返回键一起吞了）。
 */
class MapperService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MapperService? = null
            private set
    }

    private lateinit var overlay: OverlayManager
    private val handler = Handler(Looper.getMainLooper())
    private val pressed = mutableSetOf<Int>()
    private var ticking = false

    /** 总开关：关掉后按键全部放行。默认关，服务开启本身不干扰使用 */
    @Volatile
    var mappingEnabled = false

    /** 正在等待用户按下一个手柄键的那个圆点（学习绑定） */
    private var pending: MappingPoint? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayManager(this)
        overlay.onChipTap = { p -> startLearn(p) }
        overlay.onStop = { stopAll() }
        overlay.onLayoutChanged = { MappingStore.save(this, overlay.currentPoints()) }
        // 注意：这里**不自动显示浮窗**。旧版一连上就铺满屏幕，
        // 用户还没反应过来就已经挡屏了。
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** 判断按键是否真的来自手柄（而不是手机自身按键/软键盘） */
    private fun isFromPad(event: KeyEvent): Boolean {
        val dev = event.device ?: return false
        val src = dev.sources
        return (src and InputDevice.SOURCE_JOYSTICK) != 0 ||
                (src and InputDevice.SOURCE_GAMEPAD) != 0 ||
                (src and InputDevice.SOURCE_DPAD) != 0
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        // ---- 学习绑定：编辑模式下点了某个圆点，接下来按的那个键就绑给它 ----
        val wait = pending
        if (wait != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (isFromPad(event) || code >= KeyEvent.KEYCODE_BUTTON_1) {
                wait.keyCode = code
                wait.label = PadKeys.describe(code)
                pending = null
                overlay.showAwaiting(null)
                overlay.refreshLabel(wait)
                MappingStore.save(this, overlay.currentPoints())
                return true
            }
            return false
        }

        if (!mappingEnabled) return false

        // 只有被绑定过的键才拦
        val point = overlay.findPoint(code) ?: return false
        // 兜底：BACK 与手机返回键撞码，不是手柄来的就放行
        if (code == KeyEvent.KEYCODE_BACK && !isFromPad(event)) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (pressed.add(code)) {
                    overlay.highlight(point.keyCode, true)
                    ensureTicking()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (pressed.remove(code)) overlay.highlight(point.keyCode, false)
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

    private fun startLearn(p: MappingPoint) {
        if (!overlay.isEditing) return
        pending = p
        overlay.showAwaiting(p)
    }

    fun showOverlay(edit: Boolean) {
        handler.post {
            pending = null
            pressed.clear()
            overlay.show(MappingStore.load(this), edit)
        }
    }

    fun hideOverlay() {
        pending = null
        pressed.clear()
        mappingEnabled = false
        overlay.hide()
    }

    /** 一键停止：关映射 + 收浮窗，任何时候都该能回到"干净手机"状态 */
    fun stopAll() {
        hideOverlay()
    }

    fun isOverlayShowing(): Boolean = ::overlay.isInitialized && overlay.isShowing

    fun isEditing(): Boolean = ::overlay.isInitialized && overlay.isEditing

    fun pointCount(): Int = if (::overlay.isInitialized) MappingStore.load(this).size else 0

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }
}
