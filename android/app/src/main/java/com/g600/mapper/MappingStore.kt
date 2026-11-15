package com.g600.mapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个映射点。两种形态：
 *
 *  · 按键点（kind = KIND_KEY）—— 手柄某个键 → 屏幕上的一个坐标，按下就点一下。
 *  · 摇杆点（kind = KIND_STICK）—— 一个圆形区域，物理摇杆推多远，
 *    就往那个方向"按/滑"多远，用来驱动游戏里的移动摇杆和视角。
 *
 * 坐标用 0..1 的归一化值保存，换手机/换分辨率/横竖屏都能还原。
 */
data class MappingPoint(
    var keyCode: Int,
    var label: String,
    var fx: Float = 0.5f,
    var fy: Float = 0.5f,
    /**
     * false（默认）= 单击：按下时点一次就结束。点桌面图标、点技能按钮都用这个。
     * true        = 连发：按住时每 120ms 重复点击，用于游戏里需要持续按住的操作
     *               （非 root 下做不到真正的长按，只能高频连点来近似）。
     * 摇杆点用不到这个字段。
     */
    var repeat: Boolean = false,
    /** KIND_KEY / KIND_STICK */
    var kind: Int = KIND_KEY,
    /** 摇杆的输入来源：SRC_MOVE（左摇杆+方向键）/ SRC_LOOK（右摇杆） */
    var src: Int = SRC_MOVE,
    /** 摇杆半径，占屏幕短边的比例 */
    var radius: Float = 0.16f
) {
    val isStick: Boolean get() = kind == KIND_STICK
    val isLook: Boolean get() = kind == KIND_STICK && src == SRC_LOOK

    fun toJson(): JSONObject = JSONObject().apply {
        put("code", keyCode)
        put("label", label)
        put("fx", fx.toDouble())
        put("fy", fy.toDouble())
        put("repeat", repeat)
        put("kind", kind)
        put("src", src)
        put("radius", radius.toDouble())
    }

    companion object {
        const val KIND_KEY = 0
        const val KIND_STICK = 1

        /** 移动：左摇杆优先，没有摇杆数据时自动用十字键 */
        const val SRC_MOVE = 0

        /** 视角：右摇杆 */
        const val SRC_LOOK = 1

        fun fromJson(o: JSONObject) = MappingPoint(
            o.optInt("code"),
            o.optString("label"),
            o.optDouble("fx", 0.5).toFloat(),
            o.optDouble("fy", 0.5).toFloat(),
            o.optBoolean("repeat", false),
            o.optInt("kind", KIND_KEY),
            o.optInt("src", SRC_MOVE),
            o.optDouble("radius", 0.16).toFloat()
        )

        fun stick(src: Int, fx: Float, fy: Float, radius: Float) = MappingPoint(
            keyCode = 0,
            label = if (src == SRC_LOOK) "视" else "移",
            fx = fx,
            fy = fy,
            repeat = false,
            kind = KIND_STICK,
            src = src,
            radius = radius
        )
    }
}

object MappingStore {
    private const val PREF = "g600_map"
    private const val KEY = "points"
    private const val KEY_VER = "layoutVer"

    /** 1 = 只有按键；2 = 加了方向键 + 双摇杆。旧存档会自动补齐，不动用户已经摆好的位置 */
    private const val VER = 2

    fun load(ctx: Context): MutableList<MappingPoint> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null)
        val list = if (raw.isNullOrBlank()) {
            seedDefault()
        } else {
            try {
                val arr = JSONArray(raw)
                val l = mutableListOf<MappingPoint>()
                for (i in 0 until arr.length()) l.add(MappingPoint.fromJson(arr.getJSONObject(i)))
                if (l.isEmpty()) seedDefault() else l
            } catch (e: Exception) {
                seedDefault()
            }
        }
        if (sp.getInt(KEY_VER, 1) < VER) {
            ensureNewDefaults(list)
            save(ctx, list)
            sp.edit().putInt(KEY_VER, VER).apply()
        }
        return list
    }

    fun save(ctx: Context, list: List<MappingPoint>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(KEY).remove(KEY_VER).apply()
    }

    /**
     * 老版本存档升级：
     *  · 补两个摇杆圆环（老版本完全没有摇杆概念）
     *  · 方向键**不**自动加圆点：默认让十字键去驱动"移动摇杆"更实用，
     *    想把它当独立按键的人可以在主界面点「添加方向键圆点」。
     */
    private fun ensureNewDefaults(list: MutableList<MappingPoint>) {
        if (list.none { it.isStick && it.src == MappingPoint.SRC_MOVE }) {
            list.add(MappingPoint.stick(MappingPoint.SRC_MOVE, 0.17f, 0.80f, 0.15f))
        }
        if (list.none { it.isStick && it.src == MappingPoint.SRC_LOOK }) {
            list.add(MappingPoint.stick(MappingPoint.SRC_LOOK, 0.85f, 0.50f, 0.18f))
        }
    }

    /** 四个方向键圆点：只有当用户明确要"把方向键当按钮"时才加 */
    fun dpadDefaults(): List<MappingPoint> = listOf(
        MappingPoint(KeyEventCodes.DPAD_UP, "↑", 0.30f, 0.30f),
        MappingPoint(KeyEventCodes.DPAD_LEFT, "←", 0.22f, 0.38f),
        MappingPoint(KeyEventCodes.DPAD_RIGHT, "→", 0.38f, 0.38f),
        MappingPoint(KeyEventCodes.DPAD_DOWN, "↓", 0.30f, 0.46f)
    )

    /**
     * 首次运行的初始布局：按手机双摇杆游戏常见的右手键位排布，
     * 位置只是起点，用户可以拖到任意地方。
     * 注意这仅是按 Android 标准 keylayout 猜的绑定，如果实测不对，
     * 在编辑模式下单击圆点、再按手柄上的对应键即可重新绑定。
     */
    private fun seedDefault(): MutableList<MappingPoint> {
        val seed = listOf(
            Triple(KeyEventCodes.BUTTON_A, "A", 0.82f to 0.74f),
            Triple(KeyEventCodes.BUTTON_B, "B", 0.91f to 0.63f),
            Triple(KeyEventCodes.BUTTON_X, "X", 0.73f to 0.63f),
            Triple(KeyEventCodes.BUTTON_Y, "Y", 0.82f to 0.53f),
            Triple(KeyEventCodes.BUTTON_L1, "L1", 0.12f to 0.32f),
            Triple(KeyEventCodes.BUTTON_R1, "R1", 0.88f to 0.32f),
            Triple(KeyEventCodes.BUTTON_L2, "L2", 0.12f to 0.20f),
            Triple(KeyEventCodes.BUTTON_R2, "R2", 0.88f to 0.20f),
            Triple(KeyEventCodes.BUTTON_THUMBL, "L3", 0.20f to 0.62f),
            Triple(KeyEventCodes.BUTTON_THUMBR, "R3", 0.68f to 0.85f),
            Triple(KeyEventCodes.BUTTON_START, "START", 0.50f to 0.88f),
            Triple(KeyEventCodes.HELP, "!", 0.50f to 0.12f)
        )
        val list = seed.mapTo(mutableListOf()) { (code, label, pos) ->
            MappingPoint(code, label, pos.first, pos.second)
        }
        list.add(MappingPoint.stick(MappingPoint.SRC_MOVE, 0.17f, 0.80f, 0.15f))
        list.add(MappingPoint.stick(MappingPoint.SRC_LOOK, 0.85f, 0.50f, 0.18f))
        return list
    }
}

/** 只在这里集中用到 KeyEvent 常量，避免 MappingStore 顶层依赖 android.view */
private object KeyEventCodes {
    const val BUTTON_A = android.view.KeyEvent.KEYCODE_BUTTON_A
    const val BUTTON_B = android.view.KeyEvent.KEYCODE_BUTTON_B
    const val BUTTON_X = android.view.KeyEvent.KEYCODE_BUTTON_X
    const val BUTTON_Y = android.view.KeyEvent.KEYCODE_BUTTON_Y
    const val BUTTON_L1 = android.view.KeyEvent.KEYCODE_BUTTON_L1
    const val BUTTON_R1 = android.view.KeyEvent.KEYCODE_BUTTON_R1
    const val BUTTON_L2 = android.view.KeyEvent.KEYCODE_BUTTON_L2
    const val BUTTON_R2 = android.view.KeyEvent.KEYCODE_BUTTON_R2
    const val BUTTON_THUMBL = android.view.KeyEvent.KEYCODE_BUTTON_THUMBL
    const val BUTTON_THUMBR = android.view.KeyEvent.KEYCODE_BUTTON_THUMBR
    const val BUTTON_START = android.view.KeyEvent.KEYCODE_BUTTON_START
    const val HELP = android.view.KeyEvent.KEYCODE_HELP
    const val DPAD_UP = android.view.KeyEvent.KEYCODE_DPAD_UP
    const val DPAD_DOWN = android.view.KeyEvent.KEYCODE_DPAD_DOWN
    const val DPAD_LEFT = android.view.KeyEvent.KEYCODE_DPAD_LEFT
    const val DPAD_RIGHT = android.view.KeyEvent.KEYCODE_DPAD_RIGHT
}
