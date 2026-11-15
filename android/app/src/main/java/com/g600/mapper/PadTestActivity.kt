package com.g600.mapper

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
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
    private lateinit var deviceView: TextView
    private lateinit var logView: TextView
    private val lines = ArrayDeque<String>()
    private var prevMapping = false

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
            text = "按手柄上的键，下面会显示 keyCode 和它来自哪个设备。\n" +
                    "记下每个键的数字，映射不对时用「③ 显示键位」里单击圆点重新绑定。\n" +
                    "摇杆轴：无障碍服务开着时，摇杆数据会被服务接管，本页可能读不到；" +
                    "请看主界面「摇杆：…」那一行。"
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(8f), 0, dp(10f))
        })

        deviceView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8f))
        }
        root.addView(deviceView)

        axesView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#66D9EF"))
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(10f))
        }
        root.addView(axesView)

        logView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#EEEEEE"))
            typeface = Typeface.MONOSPACE
        }
        root.addView(
            ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        // 底部版本号：测试页也需要，方便确认「是不是装了新包」
        VersionFooter.attach(root, this, Color.parseColor("#777777"))

        setContentView(root)
        renderAxes(null)
        renderDevices()
    }

    override fun onResume() {
        super.onResume()
        MapperService.instance?.let {
            prevMapping = it.mappingEnabled
            it.mappingEnabled = false
        }
        renderDevices()
    }

    override fun onPause() {
        super.onPause()
        MapperService.instance?.mappingEnabled = prevMapping
    }

    /** 列出系统里所有手柄类设备，确认它到底有没有被识别成 Gamepad/Joystick */
    private fun renderDevices() {
        val sb = StringBuilder()
        val ids = InputDevice.getDeviceIds()
        var found = false
        for (id in ids) {
            val d = InputDevice.getDevice(id) ?: continue
            val src = d.sources
            val isPad = (src and InputDevice.SOURCE_GAMEPAD) != 0 ||
                    (src and InputDevice.SOURCE_JOYSTICK) != 0
            if (!isPad) continue
            found = true
            sb.append("设备: ").append(d.name).append("\n")
            sb.append("  id=").append(d.id).append("  sources=0x").append(Integer.toHexString(src)).append("\n")
            sb.append("  vid:pid=").append(d.vendorId).append(":").append(d.productId).append("\n")
        }
        deviceView.text = if (found) sb.toString().trimEnd()
        else "没有检测到手柄设备 —— 请先用蓝牙/2.4G 连上"
    }

    private fun isPadEvent(event: KeyEvent): Boolean {
        val src = event.device?.sources ?: 0
        if (src and InputDevice.SOURCE_GAMEPAD != 0) return true
        if (src and InputDevice.SOURCE_JOYSTICK != 0) return true
        val c = event.keyCode
        return c >= KeyEvent.KEYCODE_BUTTON_A
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isPadEvent(event)) {
            add("DOWN  keyCode=$keyCode  ${PadKeys.describe(keyCode)}  dev=${event.device?.name}")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isPadEvent(event)) {
            add("UP    keyCode=$keyCode  ${PadKeys.describe(keyCode)}")
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

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
        lines.addLast(line)
        while (lines.size > 40) lines.removeFirst()
        logView.text = lines.joinToString("\n")
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
