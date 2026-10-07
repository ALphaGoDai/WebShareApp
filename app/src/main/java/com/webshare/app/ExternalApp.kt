package com.webshare.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log

/**
 * 网页里"打开 App"的链接：`douban://douban.com/movie/35465232`、`weixin://…`、
 * 或者 Chrome 那套 `intent://…#Intent;scheme=…;package=…;end`。
 *
 * 两条必须做的事，缺一条就"点了没反应"：
 * ① 清单 `<queries>` 里声明这些 scheme（Android 11+ 包可见性：不声明的话，
 *    resolveActivity 一律返回 null，startActivity 也会抛 ActivityNotFoundException）；
 * ② 别把异常吞掉——旧代码 `try { startActivity() } catch (e: Exception) {}` 就是这么
 *    静默失败的，用户只看到"点豆瓣链接没反应"。
 */
object ExternalApp {

    /** 网页要打开的 App：显示用的名字/图标 + 真正要发出去的 Intent */
    class Target(val label: String, val icon: Drawable?, val intent: Intent) {
        /** intent:// 里带的兜底网址（App 打不开时网页希望改用浏览器打开） */
        val fallbackUrl: String? = intent.getStringExtra(EXTRA_FALLBACK_URL)

        companion object {
            const val EXTRA_FALLBACK_URL = "browser_fallback_url"
        }
    }

    /** 这些是网页自己的东西，交给 WebView 处理，不当成"打开别的 App" */
    private val WEB_SCHEMES = setOf(
        "http", "https", "about", "data", "blob", "javascript",
        "file", "content", "ws", "wss"
    )

    /** 认不出目标 App（没装/看不见）时，至少按 scheme 说个中国话名字 */
    private val SCHEME_LABELS = mapOf(
        "douban" to "豆瓣", "doubanmovie" to "豆瓣",
        "sinaweibo" to "微博", "weibo" to "微博", "zhihu" to "知乎",
        "bilibili" to "哔哩哔哩", "youku" to "优酷",
        "iqiyi" to "爱奇艺", "qiyi" to "爱奇艺", "qiyi-iphone" to "爱奇艺",
        "tencentvideo" to "腾讯视频", "qqlive" to "腾讯视频",
        "taobao" to "淘宝", "tmall" to "天猫",
        "alipay" to "支付宝", "alipays" to "支付宝",
        "weixin" to "微信", "snssdk1128" to "抖音",
        "xhsdiscover" to "小红书", "kwai" to "快手",
        "qqmusic" to "QQ音乐", "orpheuswidget" to "网易云音乐", "netease" to "网易云音乐",
        "openapp.jdmobile" to "京东", "pinduoduo" to "拼多多",
        "ctrip" to "携程", "meituan" to "美团", "dianping" to "大众点评",
        "suning" to "苏宁易购"
    )

    /** 网页这条链接要不要按"打开别的 App"处理 */
    fun isExternal(url: String): Boolean {
        val scheme = Uri.parse(url.trim()).scheme?.lowercase() ?: return false
        return scheme !in WEB_SCHEMES
    }

    /** 我们认识这个 scheme 吗（认识的即便不是用户点出来的也信任，页面脚本常见于跳 App） */
    fun isKnownScheme(url: String): Boolean {
        val scheme = Uri.parse(url.trim()).scheme?.lowercase() ?: return false
        return scheme in SCHEME_LABELS || url.startsWith("intent:", ignoreCase = true)
    }

    /**
     * 解析成 Intent；解析不了返回 null。
     * intent:// 按官方姿势清掉 component/selector（网页不该直接指定组件），
     * 否则就是一条普通的 VIEW Intent。
     */
    fun parse(url: String): Intent? {
        return try {
            if (url.startsWith("intent:", ignoreCase = true)) {
                val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                i.component = null
                i.selector = null
                i
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析打开 App 链接失败: $url (${e.message})")
            null
        }
    }

    /** 找出"这条链接会打开哪个 App"：优先问系统（装了才认得出），认不出就按 scheme 猜名字 */
    fun resolve(context: Context, url: String): Target? {
        val intent = parse(url) ?: return null
        val pm = context.packageManager
        var label: String? = null
        var icon: Drawable? = null
        try {
            @Suppress("DEPRECATION")
            val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (info != null) {
                label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                icon = try { info.loadIcon(pm) } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "找打开 App 的目标失败: $url (${e.message})")
        }
        val scheme = Uri.parse(url.trim()).scheme?.lowercase() ?: ""
        // 认不出就按 scheme 说个中国话名字；连 scheme 也不认识时用「其他应用」，
        // 别把包名尾段（installed 之类）当名字显示
        val name = label ?: SCHEME_LABELS[scheme] ?: "其他应用"
        return Target(name, icon, intent)
    }

    /** 真正把 App 拉起来；手机上没装（或看不见）返回 false，调用方据此给用户一句人话 */
    fun launch(context: Context, intent: Intent): Boolean {
        return try {
            val send = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(send)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "没有能打开这个链接的应用: ${intent.data ?: intent.`package`}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "打开 App 失败: ${e.message}")
            false
        }
    }

    private const val TAG = "WebShareApp"
}
