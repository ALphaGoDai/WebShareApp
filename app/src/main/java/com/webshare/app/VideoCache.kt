package com.webshare.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale

/** 一次缓存请求（从页面上发现的视频候选整理出来的、跟 WebView 无关的那几项） */
class CacheRequest(
    val url: String,
    val kind: String,
    val mime: String,
    val pageUrl: String,
    val title: String,
    /** 任务 id：缓存列表用它显示进度，也拿它当缓存条目的 id */
    val id: Long = System.currentTimeMillis(),
    /** 播放器页地址（CDN 可能只认它当 Referer，比如西瓜卡通的 pframe） */
    val referer: String = ""
)

/**
 * 缓存下来的视频：文件放在 App 自己的目录（`Android/data/<包名>/files/videocache/`），
 * 不占相册、不需要存储权限；要看的时候由 LocalMediaServer 从本机喂给 WebView。
 */
object VideoCacheStore {

    private const val TAG = "VideoCache"
    private const val INDEX = "index.json"

    class Entry(
        val id: Long,
        var title: String,
        val url: String,
        val pageUrl: String,
        val kind: String,
        var fileName: String,
        var size: Long,
        val createdAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("title", title).put("url", url).put("page", pageUrl)
            .put("kind", kind).put("file", fileName).put("size", size).put("at", createdAt)

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                o.optLong("id"), o.optString("title"), o.optString("url"), o.optString("page"),
                o.optString("kind"), o.optString("file"), o.optLong("size"), o.optLong("at")
            )
        }
    }

    fun dir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val d = File(base, "videocache")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun list(ctx: Context): List<Entry> = load(ctx).sortedByDescending { it.createdAt }

    fun find(ctx: Context, id: Long): Entry? = load(ctx).firstOrNull { it.id == id }

    fun fileFor(ctx: Context, entry: Entry): File = File(dir(ctx), entry.fileName)

    fun add(ctx: Context, entry: Entry) {
        val list = load(ctx).toMutableList()
        list.removeAll { it.id == entry.id }
        list.add(entry)
        save(ctx, list)
    }

    fun update(ctx: Context, id: Long, block: (Entry) -> Unit) {
        val list = load(ctx).toMutableList()
        list.firstOrNull { it.id == id }?.let {
            block(it)
            save(ctx, list)
        }
    }

    /** 删掉一条（连文件一起删）；文件已经不在了也算删成功 */
    fun remove(ctx: Context, id: Long): Boolean {
        val list = load(ctx).toMutableList()
        val e = list.firstOrNull { it.id == id } ?: return false
        try {
            File(dir(ctx), e.fileName).delete()
        } catch (ex: Exception) {
            Log.w(TAG, "删除缓存文件失败: ${ex.message}")
        }
        list.removeAll { it.id == id }
        save(ctx, list)
        return true
    }

    fun clear(ctx: Context) {
        for (e in load(ctx)) {
            try {
                File(dir(ctx), e.fileName).delete()
            } catch (ex: Exception) {
            }
        }
        save(ctx, emptyList())
    }

    fun totalBytes(ctx: Context): Long = load(ctx).sumOf { if (it.size > 0) it.size else 0L }

    /** 清单里有、但文件已经不见了（被清理/手动删了）的条目顺手剔掉 */
    fun prune(ctx: Context) {
        val list = load(ctx)
        val alive = list.filter { File(dir(ctx), it.fileName).let { f -> f.exists() && f.length() > 0 } }
        if (alive.size != list.size) save(ctx, alive)
    }

    private fun load(ctx: Context): List<Entry> {
        val f = File(dir(ctx), INDEX)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Entry.fromJson(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读缓存清单失败: ${e.message}")
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Entry>) {
        try {
            val arr = JSONArray()
            for (e in list) arr.put(e.toJson())
            File(dir(ctx), INDEX).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "写缓存清单失败: ${e.message}")
        }
    }
}

/**
 * 把一段网页视频拉下来存成文件。
 *
 * 支持两类：
 *  - **直链**（mp4/webm/mkv…）：整份下载；
 *  - **HLS**（.m3u8）：多码率播放列表自动选最高码率；分片按顺序拼成一个文件，
 *    TS 分片拼完试着用 MediaExtractor/MediaMuxer 转成 mp4（转不了就留 .ts）；
 *    fMP4 分片（EXT-X-MAP）把 init 段拼在最前面，直接就是 mp4。
 * 不支持的就明说：加密 HLS（EXT-X-KEY 非 NONE）、EXT-X-BYTERANGE、DASH。
 *
 * 走的是 App 自己的 HTTP 通道（HttpEngine：自定义 DoH + WebView 的 Cookie），
 * 所以登录后才能看的视频也能缓存。
 */
class VideoCacher(
    private val ctx: Context,
    private val req: CacheRequest
) {

    interface Listener {
        /** note 是"第 3/12 段"这类细话；totalBytes<0 表示总长未知 */
        fun onProgress(bytes: Long, totalBytes: Long, note: String)

        /** HLS 的分段进度：按"第几段"算百分比，bytes 是目前累计的字节数 */
        fun onSegmentProgress(current: Int, total: Int, bytes: Long) {
            onProgress(bytes, -1, "第 $current/$total 段")
        }

        /** 只更新一句状态（限速重试等），别把已经下到的字节数冲掉 */
        fun onNote(note: String) {}

        fun onDone(entry: VideoCacheStore.Entry)
        fun onError(message: String)
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    private var engine: HttpEngine? = null

    /** 在后台线程跑（阻塞），结果通过 listener 回调 */
    fun run(listener: Listener) {
        val id = req.id
        val outDir = VideoCacheStore.dir(ctx)
        val base = sanitize(req.title.ifBlank { "视频" })
        var outFile = File(outDir, "$id-$base.mp4")
        try {
            val settings = SettingsManager(ctx)
            val resolver = if (settings.dohEnabled && settings.dohUrl.isNotEmpty()) {
                try {
                    DohDnsResolver(settings.dohUrl)
                } catch (e: Exception) {
                    null
                }
            } else null
            val configuredHost = try {
                android.net.Uri.parse(settings.url.trim()).host ?: ""
            } catch (e: Exception) {
                ""
            }
            engine = HttpEngine(resolver, configuredHost)

            val kind = if (req.kind == MediaWatch.KIND_HLS) MediaWatch.KIND_HLS else MediaWatch.KIND_FILE
            if (req.kind == MediaWatch.KIND_DASH) {
                throw IllegalStateException("DASH（.mpd）视频暂不支持缓存")
            }

            var note = ""
            if (kind == MediaWatch.KIND_HLS) {
                val hls = fetchHls(outFile, listener)
                outFile = hls.first
                note = hls.second
            } else {
                downloadDirect(outFile, listener)
                // 直链也可能是个 TS（.ts 单文件）：搬成 mp4 才好在本机播放器里放；
                // 搬不动就把扩展名改回 .ts，免得播放页按 mp4 报"解码失败"
                if (isTs(req.url, outFile)) {
                    // 换个文件名转，别一边读一边写同一个文件
                    val mp4 = File(outFile.parentFile, outFile.nameWithoutExtension + "-fix.mp4")
                    if (remuxTs(outFile, mp4)) {
                        outFile.delete()
                        outFile = mp4
                        note = "直链 TS 转成了 mp4"
                    } else {
                        outFile = renameToTs(outFile)
                        note = "TS 装不进 mp4，保留 .ts（本机播放没问题）"
                    }
                }
            }

            if (cancelled) {
                deletePartial(outFile)
                return
            }
            if (!outFile.exists() || outFile.length() <= 0) {
                throw IllegalStateException("没有拿到任何数据")
            }

            val entry = VideoCacheStore.Entry(
                id = id,
                title = req.title.ifBlank { base },
                url = req.url,
                pageUrl = req.pageUrl,
                kind = kind,
                fileName = outFile.name,
                size = outFile.length(),
                createdAt = System.currentTimeMillis()
            )
            VideoCacheStore.add(ctx, entry)
            listener.onDone(entry)
            if (note.isNotEmpty()) Log.i(TAG, note)
        } catch (e: Exception) {
            if (cancelled) {
                deletePartial(outFile)
                return
            }
            Log.w(TAG, "缓存失败: ${e.message}")
            deletePartial(outFile)
            listener.onError(e.message ?: "缓存失败")
        } finally {
            engine = null
        }
    }

    /** 取消/失败时把写了一半的临时文件（直链 .mp4 或 HLS 的 .ts/.mp4）一起清掉 */
    private fun deletePartial(outFile: File) {
        val base = outFile.nameWithoutExtension
        val parent = outFile.parentFile
        for (f in listOf(outFile, File(parent, "$base.ts"), File(parent, "$base.mp4"))) {
            try {
                if (f.exists()) f.delete()
            } catch (e: Exception) {
            }
        }
    }

    // ---------- 直链 ----------

    private fun downloadDirect(outFile: File, listener: Listener) {
        val headers = baseHeaders()
        openWithRetry(req.url, headers, listener, "视频").use { resp ->
            if (resp.status !in 200..299) throw IllegalStateException("服务器返回 HTTP ${resp.status}")
            val total = resp.contentLength
            checkSpace(total)
            FileOutputStream(outFile).use { out ->
                copyStream(resp.input, out, total, listener, "")
            }
        }
    }

    // ---------- HLS ----------

    /** 返回（成品文件，附注）；附注是"TS 转成了 mp4"之类的说明 */
    private fun fetchHls(outFile: File, listener: Listener): Pair<File, String> {
        var playlistUrl = req.url
        var text = fetchText(playlistUrl, listener)
        // 多码率（master）播放列表：挑码率最高的那个
        if (text.contains("#EXT-X-STREAM-INF")) {
            val best = parseMaster(text, playlistUrl)
                ?: throw IllegalStateException("播放列表里没有可用的清晰度")
            Log.i(TAG, "HLS 选流：${best.second} bps -> ${best.first}")
            playlistUrl = best.first
            text = fetchText(playlistUrl, listener)
        }
        if (text.contains("#EXT-X-KEY")) {
            val enc = Regex("#EXT-X-KEY:[^\\n]*METHOD=([A-Z0-9-]+)").find(text)?.groupValues?.get(1)
            if (enc != null && !enc.equals("NONE", true)) {
                throw IllegalStateException("这是加密的 HLS（$enc），暂不支持缓存")
            }
        }
        if (text.contains("#EXT-X-BYTERANGE")) {
            throw IllegalStateException("这个 HLS 用了分片范围（BYTERANGE），暂不支持缓存")
        }
        val initUrl = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?.let { resolve(playlistUrl, it) }
        val segments = parseSegments(text, playlistUrl)
        if (segments.isEmpty()) throw IllegalStateException("播放列表里没有分片")
        Log.i(TAG, "HLS 分片 ${segments.size} 个，init=${initUrl ?: "无"}")

        // 先按 TS 拼（fMP4 的话 init 在最前面，拼出来就是 mp4）
        val isFmp4 = initUrl != null
        val raw = File(outFile.parentFile, outFile.nameWithoutExtension + if (isFmp4) ".mp4" else ".ts")
        FileOutputStream(raw).use { out ->
            var written = 0L
            if (initUrl != null) {
                written += fetchTo(out, initUrl, -1, listener, "初始化段")
            }
            segments.forEachIndexed { i, url ->
                if (cancelled) return@use
                val note = "第 ${i + 1}/${segments.size} 段"
                written += fetchTo(out, url, -1, listener, note)
                // 分片数量是已知的，按"第几段"给进度条，比字节数直观
                listener.onSegmentProgress(i + 1, segments.size, written)
            }
        }
        if (isFmp4) return raw to "fMP4 分片，直接拼成 mp4"

        // TS：能转 mp4 就转（转出来的文件任何播放器都认，也方便分享出去）
        val mp4 = File(outFile.parentFile, outFile.nameWithoutExtension + ".mp4")
        return if (remuxTs(raw, mp4)) {
            raw.delete()
            mp4 to "TS 分片已转成 mp4"
        } else {
            raw to "TS 分片直接拼成 .ts（本机播放没问题，个别播放器可能不认这个格式）"
        }
    }

    /** 多码率播放列表里挑 BANDWIDTH 最高的那路 */
    private fun parseMaster(text: String, base: String): Pair<String, Long>? {
        var best: Pair<String, Long>? = null
        var pendingBandwidth = -1L
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                pendingBandwidth = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            } else if (l.isNotEmpty() && !l.startsWith("#") && pendingBandwidth >= 0) {
                val url = resolve(base, l)
                if (best == null || pendingBandwidth > best!!.second) best = url to pendingBandwidth
                pendingBandwidth = -1L
            }
        }
        return best
    }

    /** 分片地址（跳过注释行；播放列表里的相对地址按播放列表地址解析） */
    private fun parseSegments(text: String, base: String): List<String> {
        val out = mutableListOf<String>()
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            out.add(resolve(base, l))
        }
        return out
    }

    private fun fetchText(url: String, listener: Listener): String {
        openWithRetry(url, baseHeaders() + ("Accept" to "*/*"), listener, "播放列表").use { resp ->
            if (resp.status !in 200..299) throw IllegalStateException("播放列表返回 HTTP ${resp.status}（$url）")
            return resp.input.readBytes().toString(Charsets.UTF_8)
        }
    }

    /** 下载一个分片，返回写进去的字节数 */
    private fun fetchTo(
        out: java.io.OutputStream,
        url: String,
        total: Long,
        listener: Listener,
        note: String
    ): Long {
        openWithRetry(url, baseHeaders(), listener, "分片").use { resp ->
            if (resp.status !in 200..299) throw IllegalStateException("分片返回 HTTP ${resp.status}")
            return copyStream(
                resp.input, out,
                if (resp.contentLength > 0) resp.contentLength else total,
                listener, note,
                // 分片内部不报字节进度：进度条按"第几段"走，字节数用累计值
                notify = false
            )
        }
    }

    // ---------- 共用 ----------

    private fun baseHeaders(): Map<String, String> {
        val h = mutableMapOf<String, String>()
        h["Accept"] = "*/*"
        // 优先用发现视频的那次请求自己的 Referer（西瓜卡通的 CDN 认 pframe 这个播放器页）
        val referer = req.referer.ifBlank { req.pageUrl }
        if (referer.isNotEmpty()) h["Referer"] = referer
        try {
            h["User-Agent"] = android.webkit.WebSettings.getDefaultUserAgent(ctx)
        } catch (e: Exception) {
        }
        return h
    }

    /**
     * 发请求；429 / 5xx 按住 Retry-After 等一会儿再试。
     *
     * 西瓜卡通的 CDN 会在一小串分片之后回 429 + `Retry-After: 24`，播放器自己就有重试，
     * 缓存器以前是拿到 429 直接判失败（用户看到的就是"这个站下不了"）。
     */
    private fun openWithRetry(
        url: String,
        headers: Map<String, String>,
        listener: Listener,
        what: String
    ): HttpEngine.StreamedResponse {
        var attempt = 0
        while (true) {
            if (cancelled) throw IllegalStateException("已取消")
            val resp = engine!!.open(url, headers)
            if (resp.status in 200..299) return resp

            val status = resp.status
            val retryable = status == 429 || status in 500..599
            if (!retryable || attempt >= MAX_RETRIES) {
                resp.close()
                throw IllegalStateException(
                    if (status == 429) "服务器限速（HTTP 429），重试 $MAX_RETRIES 次仍失败"
                    else "$what 返回 HTTP $status"
                )
            }

            val waitMs = (retryAfterMs(resp.header("Retry-After")) ?: (1500L shl attempt))
                .coerceIn(1200L, 30_000L)
            resp.close()
            attempt++
            val seconds = (waitMs + 999) / 1000
            val reason = if (status == 429) "服务器限速" else "服务器返回 HTTP $status"
            listener.onNote("$reason，等待 $seconds 秒后重试（$attempt/$MAX_RETRIES）")

            var slept = 0L
            while (slept < waitMs) {
                if (cancelled) throw IllegalStateException("已取消")
                val step = minOf(250L, waitMs - slept)
                Thread.sleep(step)
                slept += step
            }
        }
    }

    /** Retry-After 可能是秒数，也可能是 HTTP 日期 */
    private fun retryAfterMs(value: String?): Long? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        v.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
        return try {
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            val at = fmt.parse(v)?.time ?: return null
            (at - System.currentTimeMillis()).coerceAtLeast(0)
        } catch (e: Exception) {
            null
        }
    }

    private fun copyStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
        listener: Listener,
        note: String,
        notify: Boolean = true
    ): Long {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var last = 0L
        while (!cancelled) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            done += n
            if (notify) {
                val now = System.currentTimeMillis()
                if (now - last > 400) {
                    last = now
                    listener.onProgress(done, total, note)
                }
            }
        }
        output.flush()
        if (notify) listener.onProgress(done, total, note)
        if (cancelled) throw IllegalStateException("已取消")
        return done
    }

    /** 剩余空间够不够（知道总长时先看一眼，别下到一半才炸） */
    private fun checkSpace(total: Long) {
        if (total <= 0) return
        val dir = VideoCacheStore.dir(ctx)
        val free = dir.usableSpace
        if (free in 1 until (total + total / 10)) {
            throw IllegalStateException(
                "空间不够：这个视频约 ${DownloadService.fmtSize(total)}，可用 ${DownloadService.fmtSize(free)}"
            )
        }
    }

    /** 相对地址 → 绝对地址 */
    private fun resolve(base: String, ref: String): String {
        return try {
            val b = java.net.URI(base)
            b.resolve(ref).toString()
        } catch (e: Exception) {
            ref
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_").trim().take(40).ifEmpty { "视频" }

    /** 这个直链是不是 TS（地址带 .ts/.m2ts，或者下下来的头几个字节就是 TS 的同步字节 0x47…） */
    private fun isTs(url: String, file: File): Boolean {
        val path = try {
            android.net.Uri.parse(url).path?.lowercase(Locale.US) ?: ""
        } catch (e: Exception) {
            ""
        }
        if (path.endsWith(".ts") || path.endsWith(".m2ts") || path.endsWith(".mts")) return true
        return try {
            val head = ByteArray(3)
            java.io.FileInputStream(file).use { it.read(head) }
            head[0] == 0x47.toByte() && head[1] == 0x47.toByte() && head[2] == 0x47.toByte()
        } catch (e: Exception) {
            false
        }
    }

    /** 转不成 mp4 时把扩展名改成 .ts（播放页据此报 video/mp2t，播放器才知道拿它怎么办） */
    private fun renameToTs(file: File): File {
        val ts = File(file.parentFile, file.nameWithoutExtension + ".ts")
        return if (file.renameTo(ts)) ts else file
    }

    /**
     * MPEG-TS → MP4（只换封装、不重新编码，几秒钟的事）。
     * 视频/音频轨照搬，时间戳保持原样；转不了（编解码器 MediaMuxer 不认）就返回 false，留 .ts。
     */
    private fun remuxTs(src: File, dst: File): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(src.absolutePath)
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()   // extractor 轨号 -> muxer 轨号
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                map[i] = muxer.addTrack(f)
                extractor.selectTrack(i)
            }
            if (map.isEmpty()) {
                Log.w(TAG, "TS 里没有可搬的轨")
                try {
                    dst.delete()
                } catch (e: Exception) {
                }
                return false
            }
            muxer.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (!cancelled) {
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val track = extractor.sampleTrackIndex
                val outTrack = map[track]
                if (outTrack == null) {
                    extractor.advance()
                    continue
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(outTrack, buf, info)
                extractor.advance()
            }
            muxer.stop()
            true
        } catch (e: Exception) {
            Log.w(TAG, "TS 转 mp4 失败（留 .ts）: ${e.message}")
            try {
                dst.delete()
            } catch (ex: Exception) {
            }
            false
        } finally {
            try {
                muxer?.release()
            } catch (e: Exception) {
            }
            try {
                extractor?.release()
            } catch (e: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "VideoCache"
        private const val MAX_RETRIES = 6

        /** 给通知用的大小文案 */
        fun fmt(bytes: Long): String = if (bytes < 0) "未知大小" else String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
