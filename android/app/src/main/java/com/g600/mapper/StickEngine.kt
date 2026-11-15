package com.g600.mapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.IdentityHashMap
import kotlin.math.hypot

/**
 * 虚拟摇杆引擎：把「物理摇杆 / 方向键」变成屏幕上的触摸动作。
 *
 * 【为什么做不到"真·摇杆"】
 * 无障碍注入的手势天生是一次性的：
 *   · dispatchGesture 每次派发都会取消上一条手势；
 *   · StrokeDescription 时长一到，手指必定抬起，没法无限期按住。
 * 所以这里用两种"近似"来换稳定：
 *
 *  1. 移动摇杆 —— 在「摇杆中心 → 该方向」的固定点上**长按**（每次 3 秒，自动续）。
 *     前提：游戏里把移动摇杆设成「固定」（和平精英默认就是固定）。
 *     固定摇杆的判定是"手指相对摇杆中心的偏移"，所以手指直接按在偏移点 =
 *     相当于把摇杆推到底，人物就会持续跑。方向变了就挪个位置重按。
 *
 *  2. 视角摇杆 —— 从摇杆中心向该方向**反复短滑**（约每秒 8 次）。
 *     视角是"滑多少转多少"，连续短滑就等于匀速转向。
 *
 * 两者都带自愈：手势被别的按键（点射/开火）取消后，下一拍（60ms）会自动重按，
 * 所以"边跑边开火"只是移动会有一点点顿，不会彻底断掉。
 */
class StickEngine(
    private val svc: AccessibilityService,
    private val overlay: OverlayManager,
    private val enabled: () -> Boolean
) {

    companion object {
        /** 输入轮询间隔(ms)。越小越跟手，60ms ≈ 16 次/秒，够用又不费电 */
        private const val TICK_MS = 60L

        /** 移动摇杆一次"长按"的时长。到点会自动重按，中间有约 20ms 抬手 */
        private const val PRESS_MS = 3000L

        /** 目标点移动超过这么多像素才重按（避免手指微抖就反复重按） */
        private const val MOVE_EPS = 16f

        /** 视角摇杆单次滑动时长 */
        private const val SWIPE_MS = 90L

        /** 视角摇杆两次滑动的间隔 */
        private const val SWIPE_GAP = 125L

        /** 死区：摇杆量的模长小于它就当作没推 */
        private const val DEAD = 0.30f

        /** 推到死区边缘时也至少走这么远的比例，保证越过游戏自身的死区 */
        private const val MIN_REACH = 0.40f

        /** 视角滑动长度相对半径的缩放（越大转得越快） */
        private const val LOOK_SCALE = 0.62f

        /** 方向键的"虚拟推杆量" */
        private const val DPAD_MAG = 0.80f

        private const val D_UP = 1
        private const val D_DOWN = 2
        private const val D_LEFT = 4
        private const val D_RIGHT = 8
    }

    private val handler = Handler(Looper.getMainLooper())
    private val lives = IdentityHashMap<MappingPoint, Live>()
    private var ticking = false

    // ---- 原始输入 ----
    private var axLx = 0f
    private var axLy = 0f
    private var axRx = 0f
    private var axRy = 0f
    private var hatMask = 0
    private var keyMask = 0

    /** 是否收到过摇杆数据（给主界面显示诊断用） */
    var inputSeen = false
        private set

    /** 最近一次的原始轴值，主界面拿来展示 */
    var lastText = ""
        private set

    private class Live {
        var pressing = false
        var tx = 0f
        var ty = 0f
        var downAt = 0L
        var lastSwipe = 0L
        /** 每次派发 +1，用于丢弃过期的手势回调 */
        var gen = 0
    }

    // ---------------- 外部输入 ----------------

    /** 摇杆轴（Android 14+ 的 onMotionEvent 送进来） */
    fun onAxes(lx: Float, ly: Float, rx: Float, ry: Float, hx: Float, hy: Float) {
        axLx = lx
        axLy = ly
        axRx = rx
        axRy = ry
        hatMask = maskOf(hx, hy)
        inputSeen = true
        lastText = "左 %.2f,%.2f  右 %.2f,%.2f".format(lx, ly, rx, ry)
        kick()
    }

    /** 十字键（按键通道）。方向键既能当独立按键，也能驱动"移动摇杆" */
    fun onDpad(down: Boolean, code: Int) {
        val bit = when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> D_UP
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> D_DOWN
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> D_LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> D_RIGHT
            else -> 0
        }
        if (bit == 0) return
        keyMask = if (down) keyMask or bit else keyMask and bit.inv()
        kick()
    }

    private fun maskOf(hx: Float, hy: Float): Int {
        var m = 0
        if (hy < -0.5f) m = m or D_UP
        if (hy > 0.5f) m = m or D_DOWN
        if (hx < -0.5f) m = m or D_LEFT
        if (hx > 0.5f) m = m or D_RIGHT
        return m
    }

    /** 浮窗重建时清空状态 */
    fun reset() {
        handler.removeCallbacksAndMessages(null)
        ticking = false
        lives.clear()
    }

    /** 全部松手：把还按着的摇杆点回中，避免"关掉映射了人物还在跑" */
    fun releaseAll() {
        for ((p, l) in lives) {
            if (!l.pressing) continue
            l.pressing = false
            val c = overlay.pixelOf(p) ?: continue
            tap(c.first, c.second, 25L)
        }
        lives.clear()
        keyMask = 0
        hatMask = 0
        axLx = 0f
        axLy = 0f
        axRx = 0f
        axRy = 0f
        handler.removeCallbacksAndMessages(null)
        ticking = false
    }

    /** 外部（比如刚打开映射）主动唤醒轮询 */
    fun kick() {
        if (ticking || !enabled()) return
        if (overlay.stickPoints().isEmpty()) return
        ticking = true
        handler.post(ticker)
    }

    // ---------------- 轮询 ----------------

    private val ticker = object : Runnable {
        override fun run() {
            if (!enabled()) {
                ticking = false
                return
            }
            step()
            if (busy()) handler.postDelayed(this, TICK_MS) else ticking = false
        }
    }

    private fun busy(): Boolean =
        lives.values.any { it.pressing } || anyInput()

    private fun anyInput(): Boolean =
        hypot(axLx, axLy) >= DEAD || hypot(axRx, axRy) >= DEAD ||
                (hatMask or keyMask) != 0

    private fun step() {
        val sticks = overlay.stickPoints()
        if (sticks.isEmpty()) {
            lives.clear()
            return
        }
        // 清掉已经被删掉的摇杆
        lives.keys.retainAll { k -> sticks.any { it === k } }

        val now = SystemClock.uptimeMillis()
        for (p in sticks) {
            val center = overlay.pixelOf(p) ?: continue
            val v = vectorFor(p.src)
            val mag = hypot(v[0], v[1])
            val l = liveOf(p)
            if (mag < DEAD) {
                release(l, center)
                continue
            }
            val r = overlay.stickRadiusPx(p)
            val k = ((mag - DEAD) / (1f - DEAD)).coerceIn(0f, 1f)
            val reach = r * (MIN_REACH + (1f - MIN_REACH) * k)
            val ux = v[0] / mag
            val uy = v[1] / mag
            val tx = center.first + ux * reach
            val ty = center.second + uy * reach
            if (p.isLook) swipe(l, center, tx, ty, now) else hold(l, center, tx, ty, now)
        }
    }

    private fun vectorFor(src: Int): FloatArray {
        return if (src == MappingPoint.SRC_LOOK) {
            floatArrayOf(axRx, axRy)
        } else {
            // 移动摇杆：优先用左摇杆轴，没有就用方向键（老固件/系统只给 hat 的场景）
            if (hypot(axLx, axLy) >= DEAD) floatArrayOf(axLx, axLy) else dpadVector()
        }
    }

    private fun dpadVector(): FloatArray {
        val m = hatMask or keyMask
        if (m == 0) return floatArrayOf(0f, 0f)
        var x = 0f
        var y = 0f
        if (m and D_UP != 0) y -= 1f
        if (m and D_DOWN != 0) y += 1f
        if (m and D_LEFT != 0) x -= 1f
        if (m and D_RIGHT != 0) x += 1f
        val len = hypot(x, y)
        if (len == 0f) return floatArrayOf(0f, 0f)
        return floatArrayOf(x / len * DPAD_MAG, y / len * DPAD_MAG)
    }

    private fun liveOf(p: MappingPoint): Live =
        lives.getOrPut(p) { Live() }

    // ---------------- 移动摇杆：长按 ----------------

    private fun hold(
        l: Live,
        c: Pair<Float, Float>,
        tx: Float,
        ty: Float,
        now: Long
    ) {
        if (!l.pressing) {
            press(l, tx, ty, now)
            return
        }
        val moved = hypot(tx - l.tx, ty - l.ty)
        // 方向变了，或者快到期了，就重按一次（旧手势会被新手势自然取消）
        if (moved > MOVE_EPS || now - l.downAt > PRESS_MS - 500L) {
            press(l, tx, ty, now)
        }
    }

    private fun press(l: Live, tx: Float, ty: Float, now: Long) {
        val path = Path().apply {
            moveTo(tx, ty)
            // 亚像素位移：路径非零才有效，位移小到游戏分辨不出是滑动
            lineTo(tx + 0.4f, ty + 0.4f)
        }
        l.pressing = true
        l.tx = tx
        l.ty = ty
        l.downAt = now
        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, PRESS_MS))
                .build(), l
        ) { l.pressing = false }
    }

    private fun release(l: Live, c: Pair<Float, Float>) {
        if (!l.pressing) return
        l.pressing = false
        // 在摇杆中心点一下：取消长按 → 摇杆回中 → 人物停下
        tap(c.first, c.second, 25L)
    }

    // ---------------- 视角摇杆：连滑 ----------------

    private fun swipe(l: Live, c: Pair<Float, Float>, tx: Float, ty: Float, now: Long) {
        if (now - l.lastSwipe < SWIPE_GAP) return
        l.lastSwipe = now
        val sx = c.first + (tx - c.first) * LOOK_SCALE
        val sy = c.second + (ty - c.second) * LOOK_SCALE
        val path = Path().apply {
            moveTo(c.first, c.second)
            lineTo(sx, sy)
        }
        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_MS))
                .build(), l
        )
    }

    // ---------------- 手势派发 ----------------

    private fun tap(x: Float, y: Float, ms: Long) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.4f, y + 0.4f)
        }
        try {
            svc.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                    .build(), null, null
            )
        } catch (_: Throwable) {
        }
    }

    private fun dispatch(g: GestureDescription, l: Live, onEnd: (() -> Unit)? = null) {
        val gen = ++l.gen
        val end = onEnd
        val cb = if (end == null) null else object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (l.gen == gen) end()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (l.gen == gen) end()
            }
        }
        val ok = try {
            svc.dispatchGesture(g, cb, null)
        } catch (_: Throwable) {
            false
        }
        if (!ok && end != null && l.gen == gen) end()
    }
}
