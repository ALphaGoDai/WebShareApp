package com.webshare.app

import android.net.Uri
import android.util.Log
import android.webkit.WebView
import java.util.Locale

/**
 * 网页里出现过的视频候选，给「缓存视频」用。
 *
 * 只记"看起来是视频源"的请求：直链（mp4/webm/mkv…）、HLS 播放列表（.m3u8）、DASH（.mpd）。
 * **HLS 的分片请求（.ts / .m4s / 音频轨）不单独当候选**——它们要靠播放列表才拼得回来，
 * 而且一旦这一页已经有播放列表，后面那些分片都会被认为是它的一部分，不再往列表里塞。
 *
 * 记录发生在 WebViewClient 拦请求的线程上（不是主线程），所以这里自己加锁；
 * UI 想刷新就挂 [onChange]（回调里自己 post 到主线程）。
 */
object MediaWatch {

    private const val TAG = "MediaWatch"

    const val KIND_HLS = "hls"
    const val KIND_FILE = "file"
    const val KIND_DASH = "dash"

    /** 直链视频的扩展名（.ts 不在这里：那是 HLS 分片，单独看） */
    private val FILE_EXTS = listOf(
        ".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".flv", ".wmv",
        ".3gp", ".3gpp", ".mpg", ".mpeg", ".rmvb", ".vob", ".ogv"
    )

    /** HLS/DASH 的分片与辅助文件：只有在"还没有播放列表"时才会被当成直链候选 */
    private val SEGMENT_EXTS = listOf(".ts", ".m2ts", ".m4s", ".cmfv", ".cmfa", ".aac", ".vtt", ".key")

    /** 每个页面最多留几个候选（多了也没人看） */
    private const val MAX_PER_VIEW = 6

    class Item(
        val url: String,
        val kind: String,
        var mime: String,
        val viewId: Int,
        val pageUrl: String,
        val at: Long
    ) {
        var size: Long = -1L

        /** 人类可读的类型 */
        val kindLabel: String
            get() = when (kind) {
                KIND_HLS -> "HLS 流（分片）"
                KIND_DASH -> "DASH 流"
                else -> {
                    val m = mime.substringBefore(';').trim()
                    if (m.startsWith("video/")) m else ext(url).ifEmpty { "视频" }
                }
            }

        /** 缓存下来的文件建议叫什么（不含扩展名） */
        val baseName: String
            get() {
                val name = try {
                    Uri.decode(Uri.parse(url).lastPathSegment ?: "")
                } catch (e: Exception) {
                    ""
                }.trim()
                var base = name
                for (e in FILE_EXTS + SEGMENT_EXTS + listOf(".m3u8", ".mpd", ".m3u")) {
                    if (base.lowercase().endsWith(e)) {
                        base = base.substring(0, base.length - e.length)
                        break
                    }
                }
                base = base.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_', '.', '-')
                return base.takeIf { it.isNotEmpty() } ?: "视频"
            }

        /** 弹窗里显示用：主机名 + 路径末段 */
        val shortUrl: String
            get() = try {
                val u = Uri.parse(url)
                val tail = u.lastPathSegment ?: ""
                val host = u.host ?: ""
                if (tail.isEmpty()) host else "$host/…/${Uri.decode(tail).take(40)}"
            } catch (e: Exception) {
                url.take(60)
            }
    }

    private val items = mutableListOf<Item>()

    /** 每个页面当前在哪个地址上（由主线程的 onPageStarted 记。
     *  ⚠️ 记候选是在 WebViewClient 的线程上跑的，那里不能碰 WebView 的任何方法
     *  —— view.url 会抛「A WebView method was called on thread」，所以地址从这里取。 */
    private val pageUrls = HashMap<Int, String>()

    /** 列表有变化时回调（在调用线程上；UI 自己 post） */
    var onChange: (() -> Unit)? = null

    /** 主线程调用：记住这个 WebView 现在在哪个页面 */
    fun setPageUrl(view: WebView?, url: String?) {
        val viewId = System.identityHashCode(view)
        if (url.isNullOrEmpty()) return
        synchronized(items) { pageUrls[viewId] = url }
    }

    /** 这个请求像不像视频源？像就记下来（会在 WebView 的拦截线程上被调用：别碰 WebView 方法） */
    fun observe(view: WebView?, urlStr: String, headers: Map<String, String>) {
        val kind = classify(urlStr) ?: return
        addCandidate(System.identityHashCode(view), urlStr, kind, mimeFromHeaders(headers))
    }

    /**
     * 靠响应头认出来的视频：站点常用 /video/12345、/stream?src=… 这种没有扩展名的地址，
     * 只看地址认不出来，但服务器回的 Content-Type 是 video 打头的那种 —— 响应里能看到。
     */
    fun observeByMime(view: WebView?, urlStr: String, mime: String?) {
        if (mime.isNullOrEmpty() || !mime.startsWith("video/")) return
        if (classify(urlStr) != null) return   // 地址本身就能认，交给 observe 那一套（免得记两条）
        addCandidate(System.identityHashCode(view), urlStr, KIND_FILE, mime)
    }

    private fun addCandidate(viewId: Int, urlStr: String, kind: String, mime: String) {
        val pageUrl = synchronized(items) { pageUrls[viewId] } ?: ""
        var added = false
        synchronized(items) {
            // 同一个地址只留一条（播放器常对同一分片/同一文件连发好几个请求）
            if (items.any { it.viewId == viewId && it.url == urlStr }) return
            if (kind == KIND_HLS || kind == KIND_DASH) {
                // 有播放列表了：同一页里其它分片/片段都不再当候选（避免列表被塞满）
                items.removeAll { it.viewId == viewId && it.kind == KIND_FILE }
            } else if (items.any { it.viewId == viewId && (it.kind == KIND_HLS || it.kind == KIND_DASH) }) {
                return
            }
            items.add(Item(urlStr, kind, mime, viewId, pageUrl, System.currentTimeMillis()))
            while (items.count { it.viewId == viewId } > MAX_PER_VIEW) {
                val oldest = items.filter { it.viewId == viewId }.minByOrNull { it.at } ?: break
                items.remove(oldest)
            }
            added = true
        }
        if (added) {
            Log.i(TAG, "发现视频候选：$kind $urlStr")
            onChange?.invoke()
        }
    }

    /** 拿到响应后就补上真实 mime / 大小（直链整份交付时会知道总长） */
    fun noteResponse(urlStr: String, mime: String?, size: Long) {
        var changed = false
        synchronized(items) {
            for (it in items) {
                if (it.url != urlStr) continue
                if (!mime.isNullOrEmpty() && it.mime.isEmpty()) {
                    it.mime = mime
                    changed = true
                }
                if (size > 0 && it.size != size) {
                    it.size = size
                    changed = true
                }
            }
        }
        if (changed) onChange?.invoke()
    }

    /** 这一页（这个 WebView）发现的候选：播放列表优先，其次新的在前 */
    fun forView(view: WebView?): List<Item> {
        val viewId = System.identityHashCode(view)
        synchronized(items) {
            return items.filter { it.viewId == viewId }
                .sortedWith(compareBy({ if (it.kind == KIND_FILE) 1 else 0 }, { -it.at }))
        }
    }

    fun clearView(view: WebView?) {
        val viewId = System.identityHashCode(view)
        synchronized(items) {
            items.removeAll { it.viewId == viewId }
            pageUrls.remove(viewId)
        }
    }

    /** 这个地址像不像视频源；不像返回 null */
    fun classify(urlStr: String): String? {
        val u = try {
            Uri.parse(urlStr)
        } catch (e: Exception) {
            return null
        }
        val scheme = u.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = u.host ?: return null
        // 本机（离线播放页）和分享用的虚拟地址不算
        if (host == "127.0.0.1" || host.equals("localhost", true) || host == "::1") return null
        val path = (u.path ?: "").lowercase(Locale.US)
        if (path.startsWith(DohWebViewClient.SHARED_PATH_PREFIX)) return null
        val noQuery = path.substringBefore('?')
        return when {
            noQuery.endsWith(".m3u8") || noQuery.endsWith(".m3u") -> KIND_HLS
            noQuery.endsWith(".mpd") -> KIND_DASH
            FILE_EXTS.any { noQuery.endsWith(it) } -> KIND_FILE
            // 地址上认不出来就不猜（站点用 /video/12345 这种无扩展名地址时要靠页面里的
            // <video> 事件才认得出，那套还没做）
            else -> null
        }
    }

    private fun mimeFromHeaders(headers: Map<String, String>): String {
        headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value?.let { acc ->
            val first = acc.substringBefore(',').trim()
            if (first.startsWith("video/") || first.startsWith("audio/")) return first
        }
        return ""
    }

    private fun ext(urlStr: String): String =
        try {
            val p = Uri.parse(urlStr).path ?: ""
            val e = p.substringAfterLast('.', "")
            if (e.isEmpty() || e.length > 5) "" else ".$e"
        } catch (e: Exception) {
            ""
        }
}
