package com.g600.mapper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮键位层。
 *
 * 重要设计约束：**每个键位是一个独立的小窗口，绝不使用全屏窗口**。
 * 旧版用 MATCH_PARENT 的全屏窗口，编辑模式下必须可触才能拖动，结果整块屏幕
 * 的触摸都被这个窗口吃掉（FrameLayout 不消费事件，系统也不会再派发给下层），
 * 表现就是"手机触屏完全失效、APP 也点不开"。
 * 改成每键一个小窗口后，窗口以外的地方永远是空的，触摸天然穿透。
 */
class OverlayManager(private val svc: AccessibilityService) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val chips = mutableListOf<Chip>()
    private var control: TextView? = null
    private var editing = false
    private var points: MutableList<MappingPoint> = mutableListOf()

    /** 编辑模式下单击某个圆点（未拖动） */
    var onChipTap: ((MappingPoint) -> Unit)? = null
    /** 编辑模式下长按某个圆点：切换「单击 / 连发」 */
    var onChipLongPress: ((MappingPoint) -> Unit)? = null
    /** 点击右上角的停止球 */
    var onStop: (() -> Unit)? = null
    /** 拖动结束，位置需要落盘 */
    var onLayoutChanged: (() -> Unit)? = null

    val isShowing: Boolean get() = chips.isNotEmpty()
    val isEditing: Boolean get() = editing

    private var downRawX = 0f
    private var downRawY = 0f
    private var downAt = 0L
    private var origX = 0
    private var origY = 0
    private var moved = false

    /** 真实全屏尺寸。用 displayMetrics 会漏掉系统栏，导致点击位置整体偏上 */
    private var screenPx = Point(0, 0)

    private class Chip(
        val point: MappingPoint,
        val view: TextView,
        val params: WindowManager.LayoutParams
    )

    private fun dp(v: Float): Int =
        (v * svc.resources.displayMetrics.density).roundToInt()

    /**
     * 取真实全屏尺寸（含状态栏/导航栏区域）。
     * TYPE_ACCESSIBILITY_OVERLAY 配 FLAG_LAYOUT_IN_SCREEN 时，窗口坐标原点就是屏幕左上角，
     * 所以这里必须用「真实尺寸」而不是 displayMetrics —— 后者不含系统栏，
     * 会让映射点整体上移几十像素，点桌面图标时就点空了。
     */
    @Suppress("DEPRECATION")
    private fun refreshScreenSize() {
        screenPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            wm.defaultDisplay.getRealSize(p)
            p
        }
        if (screenPx.x <= 0 || screenPx.y <= 0) {
            // 极少数机型拿不到真实尺寸，退回 displayMetrics
            screenPx = Point(
                svc.resources.displayMetrics.widthPixels,
                svc.resources.displayMetrics.heightPixels
            )
        }
    }

    private fun screenW(): Int {
        if (screenPx.x <= 0) refreshScreenSize()
        return screenPx.x
    }

    private fun screenH(): Int {
        if (screenPx.y <= 0) refreshScreenSize()
        return screenPx.y
    }

    fun screenCenter(): Pair<Float, Float> =
        (screenW() / 2f) to (screenH() / 2f)

    private fun chipSize(): Int = dp(52f)

    /** 空闲时的透明度：编辑要看得清，锁定后压暗一点免得挡住图标文字 */
    private val idleAlpha: Float get() = if (editing) 0.75f else 0.35f

    /** repeat=true 的圆点用橙边，一眼看出哪些是「按住连发」，哪些是「单击一次」 */
    private fun chipBackground(repeat: Boolean = false): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(110, 0, 0, 0))
        setStroke(
            dp(if (repeat) 2.4f else 1.5f),
            if (repeat) Color.argb(235, 255, 170, 40) else Color.argb(210, 120, 220, 255)
        )
    }

    private fun baseParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    fun show(list: MutableList<MappingPoint>, edit: Boolean) {
        hide()
        refreshScreenSize()
        points = list
        editing = edit
        list.forEach { addChip(it) }
        addControl()
    }

    private fun addChip(p: MappingPoint) {
        val size = chipSize()
        val tv = TextView(svc).apply {
            text = p.label.ifBlank { "?" }
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = chipBackground(p.repeat)
            alpha = idleAlpha
        }
        val lp = baseParams(size, size, editing)
        lp.x = (p.fx * screenW()).toInt() - size / 2
        lp.y = (p.fy * screenH()).toInt() - size / 2
        tv.setOnTouchListener { _, e ->
            val c = chips.firstOrNull { it.view === tv }
            if (c == null) false else onTouchChip(c, e)
        }
        try {
            wm.addView(tv, lp)
        } catch (_: Exception) {
            return
        }
        chips.add(Chip(p, tv, lp))
    }

    /** 右上角停止球：任何时候都能点，是唯一的"逃生口" */
    private fun addControl() {
        if (control != null) return
        val size = dp(34f)
        val tv = TextView(svc).apply {
            text = "■"
            setTextColor(Color.argb(220, 255, 255, 255))
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(90, 200, 40, 40))
                setStroke(dp(1f), Color.argb(200, 255, 120, 120))
            }
            alpha = 0.55f
        }
        val lp = baseParams(size, size, true)
        lp.x = screenW() - size - dp(6f)
        lp.y = dp(90f)
        tv.setOnClickListener { onStop?.invoke() }
        try {
            wm.addView(tv, lp)
        } catch (_: Exception) {
            return
        }
        control = tv
    }

    private fun onTouchChip(chip: Chip, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                downAt = e.eventTime
                origX = chip.params.x
                origY = chip.params.y
                moved = false
                chip.view.alpha = 1f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!editing) return false
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (abs(dx) > dp(5f) || abs(dy) > dp(5f)) moved = true
                chip.params.x = origX + dx.toInt()
                chip.params.y = origY + dy.toInt()
                try {
                    wm.updateViewLayout(chip.view, chip.params)
                } catch (_: Exception) {
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                chip.view.alpha = idleAlpha
                if (!editing) return true
                when {
                    moved -> {
                        syncPositions()
                        onLayoutChanged?.invoke()
                    }
                    // 长按 0.5 秒 = 切换「单击 / 连发」，圆点边框变橙色
                    e.eventTime - downAt >= 500L -> {
                        onChipLongPress?.invoke(chip.point)
                        refreshChipStyle(chip)
                    }
                    else -> onChipTap?.invoke(chip.point)
                }
            }
        }
        return true
    }

    private fun refreshChipStyle(c: Chip) {
        c.view.background = chipBackground(c.point.repeat)
        c.view.text = c.point.label.ifBlank { "?" }
    }

    fun setEditing(edit: Boolean) {
        editing = edit
        chips.forEach { c ->
            c.view.alpha = idleAlpha
            c.params.flags = if (edit) {
                c.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                c.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try {
                wm.updateViewLayout(c.view, c.params)
            } catch (_: Exception) {
            }
        }
    }

    /** 把窗口坐标写回归一化坐标 */
    fun syncPositions() {
        val w = screenW().toFloat()
        val h = screenH().toFloat()
        if (w <= 0f || h <= 0f) return
        val half = chipSize() / 2f
        chips.forEach { c ->
            c.point.fx = ((c.params.x + half) / w).coerceIn(0f, 1f)
            c.point.fy = ((c.params.y + half) / h).coerceIn(0f, 1f)
        }
    }

    fun currentPoints(): List<MappingPoint> {
        syncPositions()
        return points.toList()
    }

    fun findPoint(keyCode: Int): MappingPoint? =
        points.firstOrNull { it.keyCode == keyCode && keyCode != 0 }

    /** 屏幕像素坐标（dispatchGesture 用的是全屏坐标系，与此一致） */
    fun pixelOf(p: MappingPoint): Pair<Float, Float>? {
        if (screenW() == 0 || screenH() == 0) return null
        return (p.fx * screenW()) to (p.fy * screenH())
    }

    fun refreshLabel(p: MappingPoint) {
        chips.firstOrNull { it.point === p }?.view?.text = p.label.ifBlank { "?" }
    }

    /** 学习状态下把某个点显示成"按键…" */
    fun showAwaiting(p: MappingPoint?) {
        chips.forEach { c ->
            c.view.background = chipBackground(c.point.repeat)
            if (p != null && c.point === p) {
                c.view.text = "按键…"
                c.view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(200, 230, 150, 20))
                    setStroke(dp(2f), Color.WHITE)
                }
            } else {
                c.view.text = c.point.label.ifBlank { "?" }
            }
        }
    }

    fun highlight(keyCode: Int, down: Boolean) {
        val c = chips.firstOrNull { it.point.keyCode == keyCode && keyCode != 0 } ?: return
        c.view.alpha = if (down) 1f else idleAlpha
        c.view.background = if (down) GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(210, 40, 160, 255))
            setStroke(dp(2f), Color.WHITE)
        } else chipBackground(c.point.repeat)
    }

    fun hide() {
        chips.forEach { c ->
            try {
                wm.removeView(c.view)
            } catch (_: Exception) {
            }
        }
        chips.clear()
        control?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        control = null
        points = mutableListOf()
        editing = false
    }
}
