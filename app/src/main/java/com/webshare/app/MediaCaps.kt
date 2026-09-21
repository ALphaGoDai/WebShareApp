package com.webshare.app

import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * 本机（系统媒体框架）真正能解码的编码能力，供网页查询。
 *
 * 背景：WebView 的 canPlayType() 在部分机型/ROM 上会误报——宣称支持 H.265，
 * 实际解码时抛 MEDIA_ERR_DECODE。视频站（微博、B 站、本项目这个资源站）通常按
 * canPlayType 选择码流或决定要不要服务端转码，于是给了一个本机解不了的 H.265 版本。
 *
 * 这里用 MediaCodecList 报告"系统里到底有没有这个编码族的解码器"，网页据此
 * 退回 H.264 或要求服务端转码即可正常播放。
 *
 * 只对 H.265 有意义：VP8/VP9/AV1 即使没有硬解，Chromium 自带软解（libvpx / dav1d）
 * 一样能播，不能因为"系统没解码器"就上报不支持。
 */
object MediaCaps {

    private const val TAG = "WebShareApp"

    /** 形如 {"hevc":true,"hwHevc":false,"sdk":34} */
    fun json(): String {
        var hevc = false
        var hwHevc = false
        try {
            for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (!type.equals("video/hevc", true) && !type.equals("video/h265", true)) continue
                    hevc = true
                    if (isHardware(info.name) || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated)) {
                        hwHevc = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mediaCaps failed: ${e.message}")
        }
        return "{\"hevc\":$hevc,\"hwHevc\":$hwHevc,\"sdk\":${Build.VERSION.SDK_INT}}"
    }

    /** API 29 以下没有 isHardwareAccelerated，按命名惯例兜底 */
    private fun isHardware(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        if (n.startsWith("omx.google.") || n.startsWith("c2.android.")) return false
        return n.contains(".hw.") || n.contains("qcom") || n.contains("exynos") ||
            n.contains("mtk") || n.contains("kirin") || n.startsWith("omx.")
    }
}
