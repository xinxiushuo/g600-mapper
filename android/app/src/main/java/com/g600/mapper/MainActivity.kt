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
import android.widget.Toast
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
        root.addView(btn("测试点击（屏幕中心点一下）") { testTap() })
        root.addView(btn("手柄输入测试") { startTest() })
        root.addView(btn("添加 / 恢复 移动摇杆（左下）") { addStick(0) })
        root.addView(btn("添加 / 恢复 视角摇杆（右侧）") { addStick(1) })
        root.addView(btn("添加方向键圆点 ↑↓←→（当按钮用）") { addDpad() })
        root.addView(btn("恢复默认布局") { resetLayout() })

        root.addView(hint(
            "\n使用顺序：\n" +
                "1. 「手柄输入测试」里确认按键能被识别，记下每个键显示的 keyCode；\n" +
                "2. 「③ 显示键位并编辑」→ 拖动圆点到游戏按钮位置；\n" +
                "   · 单击某个小圆点 → 再按手柄上的键，可以重新绑定这个圆点；\n" +
                "   · 长按小圆点 0.5 秒 → 切换「单击 / 连发」，连发的圆点边框是橙色；\n" +
                "     点桌面图标、点技能按钮用「单击」；要按住不放（开火/蓄力）用「连发」；\n" +
                "3. 「④ 锁定并开始映射」，切到游戏即可。\n\n" +
                "【摇杆 / 方向键】屏幕上有两个大圆环：\n" +
                "· 「移」= 移动摇杆，把圆环拖到游戏左下角摇杆的位置（圆心对准摇杆底盘中心），\n" +
                "  推手柄左摇杆、或者按十字键，人物就朝对应方向持续移动。\n" +
                "  游戏里请把「移动摇杆」设成「固定」（和平精英默认就是固定）。\n" +
                "· 「视」= 视角摇杆，拖到右边屏幕空白处，推右摇杆就是持续转向。\n" +
                "· 单击大圆环 = 切换「移 / 视」；长按大圆环 = 换大小（视角转太快就调小）。\n" +
                "· 十字键默认是驱动「移」摇杆（当方向用）；想把它当 4 个独立按钮，\n" +
                "  就点上面的「添加方向键圆点」，加完之后十字键只当按钮、不再控制移动。\n" +
                "· 手机是安卓 14 以下时系统不给摇杆数据，此时「移」只由十字键驱动。\n\n" +
                "如果按了手柄有高亮、但屏幕上什么都没发生，「测试点击」能直接告诉你\n" +
                "注入通不通：提示成功就说明服务正常，问题只在坐标或模式上。\n\n" +
                "任何时候点屏幕右上角的红色小方块，都会立刻停止映射并清掉浮窗。\n\n" +
                "限制：非 root 的无障碍注入没法真正「多指同时按住」，边跑边开火时\n" +
                "移动会有一点点顿（每次点击会把摇杆长按顶掉，60ms 内自动补回来）。"
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
            sb.append("\n摇杆：").append(svc.stickStatusText())
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

    /** 0 = 移动摇杆（左），1 = 视角摇杆（右） */
    private fun addStick(src: Int) {
        val svc = MapperService.instance
        if (svc == null) {
            Toast.makeText(this, "无障碍服务还没启动，请先点第 ② 步", Toast.LENGTH_LONG).show()
            return
        }
        svc.addStick(src)
        refreshStatus()
    }

    private fun addDpad() {
        val svc = MapperService.instance
        if (svc == null) {
            Toast.makeText(this, "无障碍服务还没启动，请先点第 ② 步", Toast.LENGTH_LONG).show()
            return
        }
        svc.addDpad()
        refreshStatus()
    }

    private fun startTest() {
        startActivity(Intent(this, PadTestActivity::class.java))
    }

    /**
     * 在屏幕中心注入一次点击。这是排查「按键有反应但屏幕不动」最快的手段：
     * 成功 → 注入链路没问题，问题只在坐标/模式上；
     * 失败 → 服务缺手势权限（canPerformGestures）或被别的无障碍占用。
     */
    private fun testTap() {
        val svc = MapperService.instance
        if (svc == null) {
            Toast.makeText(this, "无障碍服务还没启动，请先点第 ② 步", Toast.LENGTH_LONG).show()
            return
        }
        svc.testTap { ok, msg ->
            runOnUiThread {
                Toast.makeText(this, if (ok) "✓ $msg" else "✗ $msg", Toast.LENGTH_LONG).show()
            }
        }
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
