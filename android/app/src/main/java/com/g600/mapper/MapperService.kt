package com.g600.mapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

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

        /** 单次点击时长(ms)。必须小于连发间隔，否则新手势会掐掉还没完成的手势 */
        private const val TAP_MS = 45L

        /** 连发间隔(ms)。120ms ≈ 每秒 8 次，够游戏侧识别成"按住" */
        private const val REPEAT_GAP_MS = 120L
    }

    private lateinit var overlay: OverlayManager
    private val handler = Handler(Looper.getMainLooper())

    /** 当前按住的所有键 */
    private val pressed = mutableSetOf<Int>()

    /** 其中开了「连发」的键，需要按间隔重发 */
    private val holding = mutableSetOf<Int>()

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
        overlay.onChipLongPress = { p -> toggleRepeat(p) }
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
                    // 按下瞬间先点一次：单击模式的全部动作就在这一次
                    // 多指一起发，避免后发的 dispatch 取消前一个（组合键场景）
                    val keys = mutableSetOf(code)
                    keys.addAll(holding)
                    tapKeys(keys)
                    if (point.repeat) {
                        holding.add(code)
                        ensureTicking()
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                if (pressed.remove(code)) {
                    holding.remove(code)
                    overlay.highlight(point.keyCode, false)
                }
            }
        }
        return true   // 吞掉，避免游戏和映射同时响应
    }

    private val tick = object : Runnable {
        override fun run() {
            if (holding.isEmpty()) {
                ticking = false
                return
            }
            tapKeys(holding.toList())
            handler.postDelayed(this, REPEAT_GAP_MS)
        }
    }

    private fun ensureTicking() {
        if (!ticking) {
            ticking = true
            handler.post(tick)
        }
    }

    /**
     * 在若干映射点上同时注入一次「点击」。
     *
     * 旧版就是在这里出的问题，两个坑都踩了：
     *  1. 路径只有 moveTo、没有 lineTo —— 零长度路径在部分机型上不算有效点击
     *  2. 每 40ms 发一次、每次时长 50ms —— dispatchGesture 会用新手势取消旧手势，
     *     于是每个手势都在完成前被掐掉，等于一次都没点成（界面有高亮、屏幕无动作）
     *
     * 现在：时长 45ms < 间隔 120ms，每个手势都能完整走完；单击模式只发一次。
     */
    private fun tapKeys(codes: Collection<Int>) {
        if (codes.isEmpty()) return
        val pts = mutableListOf<Pair<Float, Float>>()
        for (code in codes) {
            val p = overlay.findPoint(code) ?: continue
            val xy = overlay.pixelOf(p) ?: continue
            pts.add(xy)
        }
        if (pts.isEmpty()) return
        val builder = GestureDescription.Builder()
        pts.forEach { (x, y) ->
            val path = Path().apply {
                moveTo(x, y)
                // 亚像素位移：远小于触摸阈值(~8dp)，不会被判成滑动，但能让路径非零长度
                lineTo(x + 0.5f, y + 0.5f)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
        }
        try {
            dispatchGesture(builder.build(), null, null)
        } catch (_: Exception) {
        }
    }

    /**
     * 诊断用：在屏幕中心点一下，直接验证注入通不通。
     * 返回结果通过回调给出，省得用户猜"到底是我没配好还是注入没生效"。
     */
    fun testTap(onResult: (Boolean, String) -> Unit) {
        if (!::overlay.isInitialized) {
            onResult(false, "服务还没初始化")
            return
        }
        val (cx, cy) = overlay.screenCenter()
        val path = Path().apply {
            moveTo(cx, cy)
            lineTo(cx + 0.5f, cy + 0.5f)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
        val accepted = try {
            dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onResult(true, "已注入点击（坐标 ${cx.toInt()},${cy.toInt()}）")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onResult(false, "手势被系统取消 —— 可能被别的无障碍手势占用")
                }
            }, null)
        } catch (e: Exception) {
            false
        }
        if (!accepted) {
            onResult(false, "dispatchGesture 返回 false：服务没有手势权限（canPerformGestures 未生效）")
        }
    }

    // ---------------- 供界面调用 ----------------

    private fun startLearn(p: MappingPoint) {
        if (!overlay.isEditing) return
        pending = p
        overlay.showAwaiting(p)
    }

    /** 长按圆点：在「单击一次」和「按住连发」之间切换 */
    private fun toggleRepeat(p: MappingPoint) {
        p.repeat = !p.repeat
        MappingStore.save(this, overlay.currentPoints())
        toast(
            if (p.repeat) "${p.label}：连发（按住时每 120ms 点一次）"
            else "${p.label}：单击（按一次点一次）"
        )
    }

    private fun toast(msg: String) {
        handler.post {
            try {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }
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
