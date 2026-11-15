package com.g600.mapper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 页面底部的版本号。主界面和手柄测试页都用这一个，避免两处各写一份。
 * 长按可复制（连同手机型号），方便反馈问题时一句话说清装的哪一版。
 */
object VersionFooter {

    fun attach(
        parent: LinearLayout,
        ctx: Context,
        textColor: Int = Color.parseColor("#888888")
    ) {
        val info = AppInfo.read(ctx)
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val tv = TextView(ctx).apply {
            text = info.line
            textSize = 11f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad / 2)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnLongClickListener {
                val full = info.line + " · " + android.os.Build.MANUFACTURER +
                        " " + android.os.Build.MODEL +
                        " · Android " + android.os.Build.VERSION.RELEASE
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("G600 版本", full))
                Toast.makeText(ctx, "已复制：$full", Toast.LENGTH_SHORT).show()
                true
            }
        }
        parent.addView(tv)
    }
}
