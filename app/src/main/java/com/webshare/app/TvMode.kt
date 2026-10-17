package com.webshare.app

import android.content.Context
import android.content.pm.PackageManager

/**
 * 电视判定：Leanback 桌面 / 电视类型设备。MainActivity 还能用
 * `adb … am start … --ez tv_mode true` 强制打开（记住后一直生效），
 * 缓存页/已下载页也跟着用同一份判定，模拟器上才能一起验证。
 */
object TvMode {
    fun isTv(ctx: Context): Boolean {
        val pm = ctx.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("android.hardware.type.television")
        ) return true
        return ctx.getSharedPreferences("webshare_tv", Context.MODE_PRIVATE)
            .getBoolean("forced", false)
    }
}
