package com.g600.mapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个映射点：手柄按键 -> 屏幕上的一个坐标。
 * 坐标用 0..1 的归一化值保存，换手机/换分辨率/横竖屏都能还原。
 */
data class MappingPoint(
    var keyCode: Int,
    var label: String,
    var fx: Float = 0.5f,
    var fy: Float = 0.5f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", keyCode)
        put("label", label)
        put("fx", fx.toDouble())
        put("fy", fy.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject) = MappingPoint(
            o.optInt("code"),
            o.optString("label"),
            o.optDouble("fx", 0.5).toFloat(),
            o.optDouble("fy", 0.5).toFloat()
        )
    }
}

object MappingStore {
    private const val PREF = "g600_map"
    private const val KEY = "points"

    fun load(ctx: Context): MutableList<MappingPoint> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null)
        if (raw.isNullOrBlank()) return seedDefault()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<MappingPoint>()
            for (i in 0 until arr.length()) list.add(MappingPoint.fromJson(arr.getJSONObject(i)))
            if (list.isEmpty()) seedDefault() else list
        } catch (e: Exception) {
            seedDefault()
        }
    }

    fun save(ctx: Context, list: List<MappingPoint>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

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
        return seed.mapTo(mutableListOf()) { (code, label, pos) ->
            MappingPoint(code, label, pos.first, pos.second)
        }
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
}
