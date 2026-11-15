package com.g600.mapper

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.ArrayDeque

/**
 * 手柄输入测试页。
 * 目的：确认 Android 到底把手柄认成了什么，每个物理键对应哪个 KeyCode。
 * 进入本页会临时关闭映射，避免按键被服务吞掉。
 */
class PadTestActivity : AppCompatActivity() {

    private lateinit var axesView: TextView
    private lateinit var logView: TextView
    private val lines = ArrayDeque<String>()
    private var prevMapping = true

    private val axisIds = intArrayOf(
        MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
        MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
        MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
        MotionEvent.AXIS_GAS, MotionEvent.AXIS_BRAKE
    )
    private val axisNames = arrayOf(
        "LX", "LY", "RX(Z)", "RY(RZ)", "HAT_X", "HAT_Y", "LTRIG", "RTRIG", "GAS", "BRAKE"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(28f), dp(16f), dp(16f))
            setBackgroundColor(Color.parseColor("#111111"))
        }

        root.addView(TextView(this).apply {
            text = "手柄输入测试"
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "按手柄上的键，下面应该出现对应的 KeyCode。\n如果什么都没出现，说明 Android 没把它识别成手柄。"
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(8f), 0, dp(12f))
        })

        axesView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#66D9EF"))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(12f))
        }
        root.addView(axesView)

        logView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#EEEEEE"))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(ScrollView(this).apply { addView(logView) })

        setContentView(root)
        renderAxes(null)
    }

    override fun onResume() {
        super.onResume()
        MapperService.instance?.let {
            prevMapping = it.mappingEnabled
            it.mappingEnabled = false
        }
    }

    override fun onPause() {
        super.onPause()
        MapperService.instance?.mappingEnabled = prevMapping
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isPadKey(keyCode)) {
            add("按下  ${PadKeys.label(keyCode)}   keyCode=$keyCode")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isPadKey(keyCode)) {
            add("松开  ${PadKeys.label(keyCode)}")
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isPadKey(code: Int): Boolean =
        PadKeys.isGamepadKey(code) || code >= KeyEvent.KEYCODE_BUTTON_A

    @SuppressLint("SetTextI18n")
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val src = event.source
        if (src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        ) {
            renderAxes(event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun renderAxes(event: MotionEvent?) {
        if (event == null) {
            axesView.text = "摇杆：等待输入…"
            return
        }
        val sb = StringBuilder()
        axisIds.forEachIndexed { i, id ->
            val v = event.getAxisValue(id)
            if (v != 0f || i < 6) {
                sb.append(String.format("%-7s %+.2f\n", axisNames[i], v))
            }
        }
        axesView.text = sb.toString().trimEnd()
    }

    private fun add(line: String) {
        val ts = String.format("%02d:%02d ", 0, 0)
        lines.addLast(line)
        while (lines.size > 40) lines.removeFirst()
        logView.text = lines.joinToString("\n")
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
