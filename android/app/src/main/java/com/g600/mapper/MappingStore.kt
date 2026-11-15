package com.g600.mapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个映射点：手柄按键 -> 屏幕上的一个坐标。
 * 坐标用 0..1 的归一化值保存，换手机/换分辨率/横竖屏都能还原。
 */
data class MappingPoint(
    val keyCode: Int,
    val label: String,
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

    /** 首次运行的初始布局：按 3 列铺开，方便用户拖到合适位置 */
    private fun seedDefault(): MutableList<MappingPoint> {
        val list = PadKeys.defaultLayout().toMutableList()
        list.forEachIndexed { i, p ->
            val col = i % 3
            val row = i / 3
            p.fx = 0.25f + col * 0.25f
            p.fy = 0.35f + row * 0.08f
        }
        return list
    }
}
