package com.g600.mapper

import android.content.Context
import android.os.Build

/**
 * 版本信息。
 *
 * versionName / versionCode 从 PackageManager 读，保证和 APK 里写的一致（权威值）。
 * BUILD_STAMP 是构建标记：GitHub Actions 构建前会把这一行替换成真实编译时间，
 * 本地直接编译则用下面这个手写值。作用是即使版本号没来得及 bump，
 * 也能一眼分清新包和旧包。
 */
object AppInfo {

    /** 构建标记，CI 会用真实时间覆盖这一行（见 .github/workflows/build-apk.yml） */
    const val BUILD_STAMP = "2026-11-15 11:40"

    data class Info(
        val versionName: String,
        val versionCode: Long,
        val stamp: String
    ) {
        /** 例：v1.1 (2) · 构建 2026-11-15 11:40 */
        val line: String
            get() = "v$versionName ($versionCode) · 构建 $stamp"
    }

    @Suppress("DEPRECATION")
    fun read(ctx: Context): Info {
        var name = "?"
        var code = 0L
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            name = pi.versionName ?: "?"
            code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pi.longVersionCode
            else
                pi.versionCode.toLong()
        } catch (_: Exception) {
            // 极少数 ROM 上 getPackageInfo 会抛异常，用占位值兜住，不影响其它功能
        }
        return Info(name, code, BUILD_STAMP)
    }
}
