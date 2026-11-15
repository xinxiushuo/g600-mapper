package com.g600.mapper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var editBtn: Button
    private lateinit var lockBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(32f), dp(20f), dp(20f))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(title("G600 手柄映射"))
        root.addView(hint("把手柄按键映射成屏幕上的触摸点，悬浮键位可任意拖动。"))

        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(16f), 0, dp(16f))
        }
        root.addView(statusView)

        root.addView(btn("① 允许悬浮窗（少数手机需要）") { requestOverlayPermission() })
        root.addView(btn("② 开启无障碍服务") { openAccessibilitySettings() })
        root.addView(btn("③ 拖动键位（编辑布局）") { startEdit() })
        root.addView(btn("④ 锁定并开始映射") { lockAndMap() })
        root.addView(btn("手柄输入测试") { startTest() })
        root.addView(btn("恢复默认布局") { resetLayout() })

        root.addView(hint(
            "\n使用顺序：\n" +
                "1. 先在「手柄输入测试」里确认 A/B/X/Y 等按键都能被识别；\n" +
                "2. 进「拖动键位」，把每个圆点拖到游戏里对应的按钮位置上；\n" +
                "3. 点「锁定并开始映射」，切到游戏即可。此时触摸穿透，不挡操作。\n\n" +
                "① 和 ② 是系统权限，只需授权一次。\n\n" +
                "注意：本方案用无障碍手势注入，对「需要持续按住摇杆移动」的场景支持有限，" +
                "详见项目 README 的能力边界说明。"
        ))
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        // 离开界面时把布局存一下
        MapperService.instance?.let { s ->
            if (s.isOverlayShowing()) s.reloadOverlay()
        }
        super.onPause()
    }

    private fun refreshStatus() {
        val acc = isAccessibilityEnabled()
        val ovl = canDrawOverlay()
        val lines = StringBuilder()
        lines.append(if (acc) "无障碍服务：已开启" else "无障碍服务：未开启 —— 请先点第 ② 步")
        if (acc && MapperService.instance == null) lines.append("（正在启动…）")
        if (acc && !ovl) lines.append("\n悬浮窗权限：未允许（多数手机不需要；看不到圆点再来点第 ① 步）")
        statusView.text = lines.toString()
        statusView.setTextColor(
            if (acc) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
        )
        val svc = MapperService.instance
        editBtn.isEnabled = svc != null
        lockBtn.isEnabled = svc != null
    }

    private fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            refreshStatus()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, MapperService::class.java).flattenToString()
        val cur = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return cur.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
        }
    }

    private fun startEdit() {
        val svc = MapperService.instance ?: return
        svc.setEditing(true)
        editBtn.text = "编辑中：拖动圆点到目标位置"
    }

    private fun lockAndMap() {
        val svc = MapperService.instance ?: return
        svc.setEditing(false)
        svc.mappingEnabled = true
        val pts = MappingStore.load(this)
        statusView.text = "已锁定，切到游戏试试（共 ${pts.size} 个映射点）"
    }

    private fun resetLayout() {
        MappingStore.reset(this)
        MapperService.instance?.reloadOverlay()
        refreshStatus()
    }

    private fun startTest() {
        startActivity(Intent(this, PadTestActivity::class.java))
    }

    // ---------- UI 小工具 ----------

    private fun dp(v: Float): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 22f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.START
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.parseColor("#888888"))
        setPadding(0, dp(8f), 0, dp(8f))
    }

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8f)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        if (label.startsWith("③")) editBtn = b
        if (label.startsWith("④")) lockBtn = b
        return b
    }
}
