package com.g600.mapper

import android.content.ComponentName
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
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(title("G600 手柄映射"))
        root.addView(hint("把手柄按键映射成屏幕上的触摸点，悬浮键位可任意拖动。"))

        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(16f), 0, dp(16f))
        }
        root.addView(statusView)

        root.addView(btn("① 允许悬浮窗（少数手机需要）") { requestOverlayPermission() })
        root.addView(btn("② 开启无障碍服务") { openAccessibilitySettings() })
        root.addView(btn("③ 显示键位并编辑") { startEdit() })
        root.addView(btn("④ 锁定并开始映射") { lockAndMap() })
        root.addView(btn("⑤ 停止并清除浮窗（随时可点）") { stopAll() })
        root.addView(btn("手柄输入测试") { startTest() })
        root.addView(btn("恢复默认布局") { resetLayout() })

        root.addView(hint(
            "\n使用顺序：\n" +
                "1. 「手柄输入测试」里确认按键能被识别，记下每个键显示的 keyCode；\n" +
                "2. 「③ 显示键位并编辑」→ 拖动圆点到游戏按钮位置；\n" +
                "   · 单击某个圆点 → 再按手柄上的键，可以重新绑定这个圆点；\n" +
                "   · 圆点显示的名字不对，就用这个办法重新绑一次；\n" +
                "3. 「④ 锁定并开始映射」，切到游戏即可。\n\n" +
                "任何时候点屏幕右上角的红色小方块，都会立刻停止映射并清掉浮窗。\n\n" +
                "注意：本方案用无障碍手势注入，对「需要持续按住摇杆移动」的场景支持有限。"
        ))

        // 页面底部版本号（长按可复制），方便确认装的是哪一版
        VersionFooter.attach(root, this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val acc = isAccessibilityEnabled()
        val svc = MapperService.instance
        val sb = StringBuilder()
        sb.append(if (acc) "无障碍服务：已开启" else "无障碍服务：未开启 —— 请先点第 ② 步")
        if (acc && svc == null) sb.append("（正在启动…）")
        if (svc != null) {
            sb.append("\n浮窗：").append(if (svc.isOverlayShowing()) "显示中" else "已隐藏")
            sb.append("　映射：").append(if (svc.mappingEnabled) "开启" else "关闭")
        }
        if (acc && !Settings.canDrawOverlays(this)) {
            sb.append("\n悬浮窗权限：未允许（多数手机不需要；看不到圆点再点第 ① 步）")
        }
        statusView.text = sb.toString()
        statusView.setTextColor(
            if (acc) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
        )
        editBtn.isEnabled = svc != null
        lockBtn.isEnabled = svc != null
    }

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
        svc.showOverlay(true)
        refreshStatus()
    }

    private fun lockAndMap() {
        val svc = MapperService.instance ?: return
        svc.showOverlay(false)
        svc.mappingEnabled = true
        refreshStatus()
    }

    private fun stopAll() {
        MapperService.instance?.stopAll()
        refreshStatus()
    }

    private fun resetLayout() {
        MappingStore.reset(this)
        val svc = MapperService.instance
        if (svc != null && svc.isOverlayShowing()) svc.showOverlay(true)
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
