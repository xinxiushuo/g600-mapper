package com.g600.mapper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮键位层。
 * - 编辑模式：每个点可以用手指拖动，松手自动保存归一化坐标
 * - 锁定模式：整层 FLAG_NOT_TOUCHABLE，触摸事件穿透给游戏，只做映射用
 */
class OverlayManager(private val svc: AccessibilityService) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var editing = false
    private var points: MutableList<MappingPoint> = mutableListOf()
    private val chips = mutableListOf<Pair<MappingPoint, TextView>>()

    val isShowing: Boolean get() = root != null
    val isEditing: Boolean get() = editing

    private fun dp(v: Float): Int =
        (v * svc.resources.displayMetrics.density).roundToInt()

    private fun chipBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(120, 0, 0, 0))
        setStroke(dp(1.5f), Color.argb(200, 120, 220, 255))
    }

    fun show(list: MutableList<MappingPoint>, edit: Boolean) {
        hide()
        points = list
        val fl = FrameLayout(svc)
        fl.setBackgroundColor(Color.TRANSPARENT)

        chips.clear()
        points.forEach { p ->
            val tv = TextView(svc).apply {
                text = p.label
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                background = chipBackground()
            }
            val size = dp(56f)
            val lp = FrameLayout.LayoutParams(size, size)
            fl.addView(tv, lp)
            tv.setOnTouchListener(DragListener(p, tv))
            chips.add(p to tv)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        editing = edit
        applyTouchFlag(params, edit)
        this.params = params
        root = fl
        try {
            wm.addView(fl, params)
        } catch (e: Exception) {
            root = null
            return
        }
        fl.post { layoutChips() }
    }

    private fun applyTouchFlag(params: WindowManager.LayoutParams, editable: Boolean) {
        if (editable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }

    /** 把归一化坐标还原成实际像素位置 */
    private fun layoutChips() {
        val r = root ?: return
        val w = r.width
        val h = r.height
        if (w == 0 || h == 0) return
        chips.forEach { (p, v) ->
            v.x = p.fx * w - v.width / 2f
            v.y = p.fy * h - v.height / 2f
        }
    }

    fun setEditing(edit: Boolean) {
        val r = root ?: return
        val p = params ?: return
        editing = edit
        applyTouchFlag(p, edit)
        try {
            wm.updateViewLayout(r, p)
        } catch (_: Exception) {
        }
    }

    /** 拖动结束时把像素位置写回归一化坐标 */
    fun syncPositions() {
        val r = root ?: return
        val w = r.width.toFloat()
        val h = r.height.toFloat()
        if (w <= 0 || h <= 0) return
        chips.forEach { (p, v) ->
            p.fx = ((v.x + v.width / 2f) / w).coerceIn(0f, 1f)
            p.fy = ((v.y + v.height / 2f) / h).coerceIn(0f, 1f)
        }
    }

    fun currentPoints(): List<MappingPoint> {
        syncPositions()
        return points.toList()
    }

    fun findPoint(keyCode: Int): MappingPoint? = points.firstOrNull { it.keyCode == keyCode }

    /** 屏幕像素坐标，供注入使用 */
    fun pixelOf(p: MappingPoint): Pair<Float, Float>? {
        val r = root ?: return null
        if (r.width == 0 || r.height == 0) return null
        return (p.fx * r.width) to (p.fy * r.height)
    }

    /** 高亮：按下时点亮，松开还原 */
    fun highlight(keyCode: Int, down: Boolean) {
        val pair = chips.firstOrNull { it.first.keyCode == keyCode } ?: return
        val v = pair.second
        v.alpha = if (down) 1f else 0.65f
        val bg = chipBackground()
        if (down) {
            bg.setColor(Color.argb(200, 40, 160, 255))
        }
        v.background = bg
    }

    fun hide() {
        val r = root ?: return
        try {
            wm.removeView(r)
        } catch (_: Exception) {
        }
        root = null
        params = null
        chips.clear()
    }

    private inner class DragListener(
        private val p: MappingPoint,
        private val v: View
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var originX = 0f
        private var originY = 0f
        private var moved = false

        override fun onTouch(view: View, e: MotionEvent): Boolean {
            if (!editing) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    originX = v.x
                    originY = v.y
                    moved = false
                    v.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(4f) || abs(dy) > dp(4f)) moved = true
                    v.x = originX + dx
                    v.y = originY + dy
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 0.65f
                    syncPositions()
                    (svc as? OnLayoutChanged)?.onLayoutChanged()
                }
            }
            return true
        }
    }

    interface OnLayoutChanged {
        fun onLayoutChanged()
    }
}
