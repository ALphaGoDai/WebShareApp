package com.webshare.app

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 录像文件时间轴修复（门锁 / 监控类设备常见故障）。
 *
 * 现象：这类设备（如「电子锁」目录下的门锁录像）在录制结束时，封装器会把缓冲区里
 * 剩下的采样点"压扁"写进文件——视频/音频轨道尾部有一批采样点的 PTS 挤在几十微秒内，
 * 甚至与前一帧相同，而且这批帧的数据本身也是残缺的（HEVC 的 NAL 长度字段是垃圾、
 * AAC 帧解不出声道）。桌面播放器大多能容忍，Android 的媒体框架不行：解码器拿到这种
 * 包直接报 "PIPELINE_ERROR_DECODE"，WebView 的 <video> 于是抛 MEDIA_ERR_DECODE(code 3)，
 * 页面上表现为「加载失败，正在自动重试…」→「解码失败」。
 *
 * 修复方式：**只改文件自己的采样表，采样数据一个字节都不动**。
 * 把 stts/stsz/stsc/stco/stss/ctts 截断到"塌陷段之前"，改 mdhd/tkhd/mvhd 时长，
 * 最后把 mdat 尾部那段坏数据砍掉。效果等同 ffmpeg `-t <截断点> -c copy`，但不需要 ffmpeg。
 *
 * 为什么不重新封装（v1.0.18 → v1.0.19 两次踩坑）：
 *  1) MediaExtractor 枚举采样点 + MediaMuxer 重封装：实测这类文件上 MediaExtractor 会漏掉
 *     文件自己的第 0 号采样点（PTS=0 的关键帧），开头几帧的 SAMPLE_FLAG_SYNC 也报成 0；
 *     MediaMuxer 见首个视频采样点不是关键帧，就把它之前的采样点整段丢弃 → 修出来的文件
 *     视频从 0.889s 才开始、音频从 0 开始，音画错位 0.889s，开头 11 帧画面永远丢失。
 *  2) 自己读采样表、把采样数据交给 MediaMuxer 写：MediaMuxer 对 AVC/HEVC 采样点会做
 *     "Annex-B → 长度前缀"转换，而文件里的采样点本来就是长度前缀格式，找不到起始码，
 *     于是整段被当成一个 NAL 又套一层长度前缀（每帧多 4 字节）。实测 ffmpeg 解出 0 帧、
 *     WebView 只解出 2 帧——视频画面根本没出来，只有声音在走。
 * 现在改成原文件原地改表，以上两种平台行为都不再影响结果。
 */
object MediaRepair {

    private const val TAG = "WebShareApp"

    /** 只处理中小文件：修复要在内存里改表并留副本，超大文件交给页面自己的重试逻辑 */
    const val MAX_REPAIR_BYTES = 32 * 1024 * 1024

    /** AAC 帧至少 21ms（48kHz）；低于 1ms 的间隔不可能是正常帧——门锁坏帧是 62~125µs */
    private const val MIN_SANE_DELTA_US = 1_000L

    /** 至少这么多帧被压扁才认定文件有病（避免误伤正常文件里偶然的重复时间戳） */
    private const val MIN_COLLAPSED_SAMPLES = 3

    /** 判定规则/实现变更时递增，旧缓存自动作废 */
    private const val RULE_VERSION = 4

    /** 诊断用：把修复决策过程写到缓存目录旁的小文本，方便 adb pull 出来核对（发布前删掉） */
    private const val TRACE = true

    private const val TYPE_VIDEO = 0
    private const val TYPE_AUDIO = 1

    data class Result(val bytes: ByteArray, val note: String)

    // ------------------------------------------------------------------ 入口

    /**
     * 需要时返回修复后的文件内容；不需要修复（轨道时间轴正常）或修不了时返回 null。
     * 命中磁盘缓存时直接返回缓存内容。缓存目录带 RULE_VERSION，改规则/实现后旧缓存作废。
     */
    fun repairBytes(src: ByteArray, urlKey: String, cacheDir: File): Result? {
        if (src.size < 4096 || src.size > MAX_REPAIR_BYTES) return null

        val cached = cacheFile(cacheDir, urlKey)
        if (cached != null && cached.isFile && cached.length() > 1024) {
            return try {
                Result(cached.readBytes(), "")     // 缓存命中不再打扰用户
            } catch (e: Exception) {
                null
            }
        }

        return try {
            repairInternal(src, cached)
        } catch (e: Exception) {
            Log.w(TAG, "media repair failed: ${e.message}")
            null
        }
    }

    private fun cacheFile(cacheDir: File?, urlKey: String): File? {
        if (cacheDir == null) return null
        val dir = File(cacheDir, "video-repair-r$RULE_VERSION")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val name = md5(urlKey) + ".mp4"
        return File(dir, name)
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ 数据结构

    /** 盒子在文件里的位置 */
    private class Box(val start: Int, val payload: Int, val end: Int)

    /** 时长字段的位置与宽度（mdhd/mvhd 与 tkhd 的偏移不同，解析时就记下来） */
    private class DurBox(val pos: Int, val len: Int)

    /** 一条轨道的采样表：既用来判坏帧，也记住各个表盒子在文件里的位置以便改写 */
    private class Track(
        val type: Int,
        val codec: String,
        val timescale: Int,
        val times: LongArray,          // 展示时间（µs），判塌陷用
        val offsets: IntArray,         // 采样点在文件里的绝对字节位置
        val sizes: IntArray,
        val sync: BooleanArray,
        val sttsCnt: IntArray,         // stts 条目：计数
        val sttsDelta: LongArray,      // stts 条目：增量
        val cttsCnt: IntArray,         // ctts 条目（没有就是空数组）
        val fixedSize: Int,            // stsz 的 sample_size（0 = 逐样本表）
        val stscFirst: IntArray,       // stsc 条目：first_chunk（1-based）
        val stscSpc: IntArray,         // stsc 条目：每 chunk 采样数
        val chunkFirst: IntArray,      // 每个 chunk 的第一个采样点下标
        val chunkOf: IntArray,         // 采样点 → chunk 下标
        val stcoWide: Boolean,         // stco(4 字节) 还是 co64(8 字节)
        val boxStts: Box, val boxStsz: Box, val boxStsc: Box, val boxStco: Box,
        val boxStss: Box?, val boxCtts: Box?,
        val mdhdDur: DurBox, val tkhdDur: DurBox, val boxElst: Box?
    ) {
        var cut = times.size              // 只保留 [0, cut)，其余是尾部坏帧
        val isVideo get() = type == TYPE_VIDEO
        val isAudio get() = type == TYPE_AUDIO
    }

    private class Movie(
        val tracks: List<Track>,
        val mvhdDur: DurBox,
        val movieTimescale: Int,
        val mdat: Box?,
        val mdatIsLast: Boolean
    )

    // ------------------------------------------------------------------ 修复

    private fun repairInternal(src: ByteArray, cache: File?): Result? {
        val debugFile = cache?.let { File(it.parentFile, it.nameWithoutExtension + ".trace.txt") }
        // 只有真正解析出 moov 之后才落盘 trace：MSE / HLS 那种分片请求（每个 .m4s/.ts 都过一遍
        // 拦截层）解析必然失败，没必要时每个分片都留一个文件
        var fileTrace = false
        fun trace(msg: String) {
            Log.i(TAG, "media repair: $msg")
            if (TRACE && fileTrace && debugFile != null) {
                try {
                    debugFile.appendText(msg + "\n")
                } catch (_: Exception) {
                }
            }
        }

        trace("start size=${src.size}")
        val movie = try {
            parseMovie(src) { m -> trace(m) }
        } catch (e: Exception) {
            trace("采样表解析异常 ${e.message}")
            null
        }
        if (movie == null || movie.tracks.isEmpty()) {
            trace("没解析出可用采样表，放弃")
            return null
        }
        fileTrace = true
        trace("解析出 ${movie.tracks.size} 条轨道 size=${src.size}")
        for (t in movie.tracks) {
            trace(
                "type=${t.type} ${t.codec} ts=${t.timescale} n=${t.times.size} " +
                        "first=${t.times.firstOrNull()} last=${t.times.lastOrNull()} " +
                        "collapse=${collapsedStart(t.times)} off0=${t.offsets.firstOrNull()} " +
                        "size0=${t.sizes.firstOrNull()} sync=${t.sync.count { it }}"
            )
        }

        var dropped = 0
        for (t in movie.tracks) {
            val cut = collapsedStart(t.times)
            if (cut <= 0) continue
            t.cut = cut
            dropped += t.times.size - cut
        }
        if (dropped == 0) {
            trace("时间轴正常，不修")
            return null
        }
        if (movie.tracks.none { it.isVideo && it.cut >= 2 }) {
            trace("几乎没有可用视频帧，放弃")
            return null
        }
        trace("will drop=$dropped cuts=${movie.tracks.joinToString(",") { "${it.cut}/${it.times.size}" }}")

        val out = trimInPlace(src, movie) { m -> trace(m) }

        val check = try {
            parseMovie(out) { m -> trace("out $m") }
        } catch (e: Exception) {
            null
        }
        if (check == null || !verifyOutput(check, movie)) {
            trace("输出自检不通过，放弃（out=${out.size} 字节）")
            return null
        }

        if (cache != null) {
            try {
                cache.writeBytes(out)
            } catch (e: Exception) {
                Log.w(TAG, "repair cache write failed: ${e.message}")
            }
        }

        val audioDropped = movie.tracks.filter { it.isAudio }.sumOf { it.times.size - it.cut }
        val audioGone = movie.tracks.any { it.isAudio && it.cut < 2 }
        val note = buildString {
            append("已修复录像尾部损坏帧")
            if (audioGone) append("（音轨损坏已丢弃，静音播放）")
            else if (audioDropped > 0) append("（丢弃 $audioDropped 个损坏音频帧）")
        }
        trace("ok dropped=$dropped in=${src.size} out=${out.size} note=$note")
        return Result(out, note)
    }

    /**
     * 改写采样表：每个盒子的**总长度保持不变**（多余的位置补 0），所以 mdat 里所有采样点的
     * 字节位置不变，stco/co64 里的偏移一个都不用动；最后再把 mdat 尾部的坏数据砍掉。
     */
    private fun trimInPlace(src: ByteArray, movie: Movie, trace: (String) -> Unit): ByteArray {
        val out = src.copyOf()

        for (t in movie.tracks) {
            val cut = t.cut
            val n = t.times.size
            if (cut >= n || cut < 1) continue

            // stsz：采样大小表（前缀原样保留，只改计数 + 清尾巴）
            putU32(out, t.boxStsz.payload + 8, cut.toLong())
            if (t.fixedSize <= 0) {
                zero(out, t.boxStsz.payload + 12 + cut * 4, t.boxStsz.end)
            }

            // stts：解码时长表。前若干条整条保留，最后一条可能只用到一部分
            var covered = 0
            var full = 0
            while (full < t.sttsCnt.size && covered + t.sttsCnt[full] <= cut) {
                covered += t.sttsCnt[full]
                full++
            }
            var partialCnt = 0
            if (covered < cut && full < t.sttsCnt.size) {
                partialCnt = cut - covered
            }
            val sttsCount = full + (if (partialCnt > 0) 1 else 0)
            putU32(out, t.boxStts.payload + 4, sttsCount.toLong())
            if (partialCnt > 0) putU32(out, t.boxStts.payload + 8 + full * 8, partialCnt.toLong())
            zero(out, t.boxStts.payload + 8 + sttsCount * 8, t.boxStts.end)
            var durTicks = 0L
            for (e in 0 until full) durTicks += t.sttsCnt[e] * t.sttsDelta[e]
            if (partialCnt > 0) durTicks += partialCnt.toLong() * t.sttsDelta[full]

            // ctts：展示时间偏移表（有才处理，条目自己一套计数）
            val ctts = t.boxCtts
            if (ctts != null && t.cttsCnt.isNotEmpty()) {
                var cov = 0
                var f = 0
                while (f < t.cttsCnt.size && cov + t.cttsCnt[f] <= cut) {
                    cov += t.cttsCnt[f]
                    f++
                }
                var pc = 0
                if (cov < cut) pc = cut - cov
                val cnt = f + (if (pc > 0) 1 else 0)
                putU32(out, ctts.payload + 4, cnt.toLong())
                if (pc > 0) putU32(out, ctts.payload + 8 + f * 8, pc.toLong())
                zero(out, ctts.payload + 8 + cnt * 8, ctts.end)
            }

            // stss：关键帧表（1-based 采样序号，条目递增，保留 ≤ cut 的前缀）
            val stss = t.boxStss
            if (stss != null) {
                val total = u32(src, stss.payload + 4).toInt()
                var keepCnt = 0
                for (i in 0 until total) {
                    val num = u32(src, stss.payload + 8 + i * 4).toInt()
                    if (num in 1..cut) keepCnt++ else break
                }
                putU32(out, stss.payload + 4, keepCnt.toLong())
                zero(out, stss.payload + 8 + keepCnt * 4, stss.end)
            }

            // stsc：chunk ↔ 采样点 的对应关系。保留的 chunk 是原表前缀，
            // 只有"最后一个保留 chunk 装不满"时才需要把它单独拆成一条
            val lastChunk = t.chunkOf[cut - 1]                 // 0-based
            val lastChunk1 = lastChunk + 1                     // 1-based
            val inChunk = cut - t.chunkFirst[lastChunk]
            val origSpc = spcOf(t, lastChunk)
            var used = 0
            while (used < t.stscFirst.size && t.stscFirst[used] <= lastChunk1) used++
            if (used < 1) used = 1
            if (inChunk < origSpc && t.stscFirst[used - 1] != lastChunk1) {
                // 原条目覆盖的 chunk 区间要断开：补一条只覆盖最后一个 chunk 的
                val pos = t.boxStsc.payload + 8 + used * 12
                if (pos + 12 <= t.boxStsc.end) {
                    putU32(out, pos, lastChunk1.toLong())
                    putU32(out, pos + 4, inChunk.toLong())
                    putU32(out, pos + 8, 1L)
                    putU32(out, t.boxStsc.payload + 4, (used + 1).toLong())
                    zero(out, pos + 12, t.boxStsc.end)
                } else {
                    putU32(out, t.boxStsc.payload + 4, used.toLong())
                    zero(out, t.boxStsc.payload + 8 + used * 12, t.boxStsc.end)
                }
            } else {
                if (inChunk < origSpc) {
                    putU32(out, t.boxStsc.payload + 8 + (used - 1) * 12 + 4, inChunk.toLong())
                }
                putU32(out, t.boxStsc.payload + 4, used.toLong())
                zero(out, t.boxStsc.payload + 8 + used * 12, t.boxStsc.end)
            }

            // stco/co64：chunk 偏移表（保留 chunk 的数据都在原处，偏移一个都不用改）
            putU32(out, t.boxStco.payload + 4, lastChunk1.toLong())
            val per = if (t.stcoWide) 8 else 4
            zero(out, t.boxStco.payload + 8 + lastChunk1 * per, t.boxStco.end)

            // 时长：mdhd（轨道时间刻度）→ tkhd（电影时间刻度）
            val durMovie = patchDurations(out, t, durTicks, movie.movieTimescale)
            trace("patched type=${t.type} cut=$cut durTicks=$durTicks durMovie=$durMovie")
        }

        // mvhd 取各轨最大时长
        var maxMovie = 0L
        for (t in movie.tracks) {
            val d = movieDuration(t, readDur(out, t.mdhdDur), movie.movieTimescale)
            if (d > maxMovie) maxMovie = d
        }
        if (maxMovie > 0) writeDur(out, movie.mvhdDur, maxMovie)

        // mdat 尾巴：坏帧数据都在尾部时才砍（砍之前保证不碰任何保留采样点）
        val mdat = movie.mdat
        if (movie.mdatIsLast && mdat != null) {
            var maxEnd = 0L
            var minDropped = Long.MAX_VALUE
            for (t in movie.tracks) {
                for (k in 0 until t.cut) {
                    val e = t.offsets[k].toLong() + t.sizes[k]
                    if (e > maxEnd) maxEnd = e
                }
                for (k in t.cut until t.times.size) {
                    val s = t.offsets[k].toLong()
                    if (s < minDropped) minDropped = s
                }
            }
            val header = mdat.payload - mdat.start
            // 被丢掉的那些采样点即使有数据落在 maxEnd 之前也无所谓：它们已经不在任何 chunk 里，
            // 砍掉不影响播放；唯一要保证的是保留采样点的数据一个字节都不缺
            if (maxEnd > mdat.payload && maxEnd <= mdat.end) {
                val newEnd = maxEnd.toInt()
                if (header == 16) putU64(out, mdat.start + 8, (newEnd - mdat.start).toLong())
                else putU32(out, mdat.start, (newEnd - mdat.start).toLong())
                trace("truncate mdat ${src.size} -> $newEnd")
                return out.copyOf(newEnd)
            }
            trace("keep mdat whole (maxEnd=$maxEnd minDropped=$minDropped)")
        }
        return out
    }

    /** 写入 mdhd/tkhd 时长（elst 的断点时长非 0 时同步改），返回电影时间刻度下的时长 */
    private fun patchDurations(out: ByteArray, t: Track, durTicks: Long, movieTimescale: Int): Long {
        val ticks = if (durTicks > 0) durTicks else readDur(out, t.mdhdDur)
        writeDur(out, t.mdhdDur, ticks)
        val movie = movieDuration(t, ticks, movieTimescale)
        writeDur(out, t.tkhdDur, movie)
        val elst = t.boxElst
        if (elst != null) {
            val ver = out[elst.payload].toInt() and 0xFF
            val step = if (ver == 1) 20 else 12
            val cnt = u32(out, elst.payload + 4).toInt()
            for (i in 0 until cnt) {
                val p = elst.payload + 8 + i * step
                if (p + step > elst.end) break
                val seg = if (ver == 1) u64(out, p) else u32(out, p)
                if (seg != 0L) {
                    if (ver == 1) putU64(out, p, movie) else putU32(out, p, movie)
                }
            }
        }
        return movie
    }

    private fun movieDuration(t: Track, ticks: Long, movieTimescale: Int): Long =
            if (t.timescale <= 0) 0L else ticks * movieTimescale / t.timescale

    private fun readDur(b: ByteArray, d: DurBox): Long = if (d.len == 8) u64(b, d.pos) else u32(b, d.pos)

    private fun writeDur(b: ByteArray, d: DurBox, v: Long) {
        if (d.len == 8) putU64(b, d.pos, v) else putU32(b, d.pos, v)
    }

    private fun spcOf(t: Track, chunk0: Int): Int {
        var spc = 1
        for (i in t.stscFirst.indices) {
            if (t.stscFirst[i] <= chunk0 + 1) spc = t.stscSpc[i] else break
        }
        return spc
    }

    /** 输出自检：采样表读得回来、每条轨道的保留帧数与预期一致、首帧仍在 0、时间轴不再塌陷 */
    private fun verifyOutput(check: Movie, want: Movie): Boolean {
        val v = check.tracks.firstOrNull { it.isVideo } ?: return false
        val wantVideo = want.tracks.firstOrNull { it.isVideo } ?: return false
        if (v.times.size < wantVideo.cut) return false
        if (v.times.isEmpty() || v.times[0] > 2_000L) return false
        if (collapsedStart(v.times) >= 0) return false
        for (t in want.tracks) {
            val got = check.tracks.firstOrNull { it.type == t.type } ?: continue
            if (got.times.size != t.cut) return false
        }
        return true
    }

    /**
     * 第一条塌陷采样点的下标：该点与上一点的 PTS 间隔落在 [0, 1ms) 内，且整条轨道上
     * 这样的点至少有 MIN_COLLAPSED_SAMPLES 个；没有塌陷返回 -1。
     *
     * 只把"非负且小于 1ms 的间隔"当作塌陷——门锁设备的坏帧间隔是 62µs~125µs；
     * 绝不能把负间隔（B 帧重排导致的 PTS 回退，正常视频常见）当成坏帧，否则会把
     * 健康视频拆得七零八落。全站 349 个视频回归验证：命中 24 个（全是电子锁录像），误报 0。
     */
    private fun collapsedStart(times: LongArray): Int {
        val n = times.size
        if (n < 4) return -1
        var count = 0
        var first = -1
        for (i in 1 until n) {
            val delta = times[i] - times[i - 1]
            if (delta >= 0 && delta < MIN_SANE_DELTA_US) {
                count++
                if (first < 0) first = i
            }
        }
        return if (count >= MIN_COLLAPSED_SAMPLES) first else -1
    }

    // ------------------------------------------------------------------ 字节工具

    private fun u32(b: ByteArray, p: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (b[p + i].toInt() and 0xFF).toLong()
        return v
    }

    private fun u64(b: ByteArray, p: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[p + i].toInt() and 0xFF).toLong()
        return v
    }

    private fun putU32(b: ByteArray, p: Int, v: Long) {
        b[p] = ((v ushr 24) and 0xFF).toByte()
        b[p + 1] = ((v ushr 16) and 0xFF).toByte()
        b[p + 2] = ((v ushr 8) and 0xFF).toByte()
        b[p + 3] = (v and 0xFF).toByte()
    }

    private fun putU64(b: ByteArray, p: Int, v: Long) {
        for (i in 0 until 8) b[p + i] = ((v ushr (56 - i * 8)) and 0xFF).toByte()
    }

    private fun zero(b: ByteArray, from: Int, to: Int) {
        if (from < to && from >= 0) b.fill(0, from, to)
    }

    // ------------------------------------------------------------------ MP4 采样表解析

    private fun parseMovie(data: ByteArray, trace: (String) -> Unit): Movie? =
            if (data.size < 64) null else Mp4(data) { m -> trace(m) }.movie()

    /**
     * 够用就好的 mp4 解析：认顶层 ftyp/moov/mdat，认 moov/trak/mdia/minf/stbl 里的采样表。
     * 遇到看不懂的结构就放弃（返回 null / 空表），调用方按"修不了"处理。
     */
    private class Mp4(private val d: ByteArray, private val trace: (String) -> Unit) {

        fun movie(): Movie? {
            val moov = find(0, d.size, "moov")
            if (moov == null) { trace("parse: 找不到 moov（顶层盒子扫不通）"); return null }
            trace("parse: moov=${moov.start}..${moov.end}")
            val mvhd = find(moov.payload, moov.end, "mvhd")
            if (mvhd == null) { trace("parse: 找不到 mvhd"); return null }
            val mvhdVer = d[mvhd.payload].toInt() and 0xFF
            val movieTimescale =
                    if (mvhdVer == 1) u32(d, mvhd.payload + 20).toInt()
                    else u32(d, mvhd.payload + 12).toInt()
            if (movieTimescale <= 0) { trace("parse: mvhd 时间刻度异常"); return null }
            // 时长字段：mdhd/mvhd v0 在 payload+16，v1 在 payload+24（tkhd 分别是 +20 / +28）
            val mvhdDur = if (mvhdVer == 1) DurBox(mvhd.payload + 24, 8) else DurBox(mvhd.payload + 16, 4)

            // 顶层盒子：找 mdat，并记住它是不是最后一个（是才敢砍尾巴）
            var mdat: Box? = null
            var lastStart = -1
            var p = 0
            while (p + 8 <= d.size) {
                val size = boxSize(p, d.size) ?: break
                if (fourcc(p + 4) == "mdat" && mdat == null) {
                    mdat = Box(p, p + headerLen(p), p + size)
                }
                lastStart = p
                p += size
            }
            val mdatIsLast = mdat != null && mdat.start == lastStart

            val tracks = ArrayList<Track>()
            var q = moov.payload
            while (q + 8 <= moov.end) {
                val size = boxSize(q, moov.end) ?: break
                if (fourcc(q + 4) == "trak") {
                    val t = try {
                        parseTrak(q + headerLen(q), q + size)
                    } catch (e: Exception) {
                        trace("parse: trak 解析异常 ${e}")
                        null
                    }
                    if (t != null) tracks.add(t)
                }
                q += size
            }
            return Movie(tracks, mvhdDur, movieTimescale, mdat, mdatIsLast)
        }

        private fun parseTrak(start: Int, end: Int): Track? {
            fun bail(msg: String): Track? {
                trace("parse: trak@$start 中断: $msg")
                return null
            }
            val mdia = find(start, end, "mdia") ?: return bail("没有 mdia")
            val hdlr = find(mdia.payload, mdia.end, "hdlr") ?: return bail("没有 hdlr")
            val type = when (fourcc(hdlr.payload + 8)) {
                "vide" -> TYPE_VIDEO
                "soun" -> TYPE_AUDIO
                else -> return bail("handler=${fourcc(hdlr.payload + 8)}")
            }
            val mdhd = find(mdia.payload, mdia.end, "mdhd") ?: return bail("没有 mdhd")
            val ver = d[mdhd.payload].toInt() and 0xFF
            val timescale =
                    if (ver == 1) u32(d, mdhd.payload + 20).toInt() else u32(d, mdhd.payload + 12).toInt()
            if (timescale <= 0) return bail("mdhd 时间刻度异常")
            val mdhdDur = if (ver == 1) DurBox(mdhd.payload + 24, 8) else DurBox(mdhd.payload + 16, 4)

            val tkhd = find(start, end, "tkhd") ?: return bail("没有 tkhd")
            val tkhdVer = d[tkhd.payload].toInt() and 0xFF
            val tkhdDur = if (tkhdVer == 1) DurBox(tkhd.payload + 28, 8) else DurBox(tkhd.payload + 20, 4)
            val edts = find(start, end, "edts")
            val elst = if (edts != null) find(edts.payload, edts.end, "elst") else null

            // 采样表在 mdia > minf > stbl 里（minf 这一层不能省）
            val minf = find(mdia.payload, mdia.end, "minf") ?: return bail("没有 minf")
            val stbl = find(minf.payload, minf.end, "stbl") ?: return bail("没有 stbl")
            val stsd = find(stbl.payload, stbl.end, "stsd")
            val codec = if (stsd != null && stsd.payload + 16 <= stsd.end) fourcc(stsd.payload + 12) else "?"

            val stts = find(stbl.payload, stbl.end, "stts") ?: return bail("没有 stts")
            val stsz = find(stbl.payload, stbl.end, "stsz") ?: return bail("没有 stsz")
            val stsc = find(stbl.payload, stbl.end, "stsc") ?: return bail("没有 stsc")
            val stco = find(stbl.payload, stbl.end, "stco")
                    ?: find(stbl.payload, stbl.end, "co64")
                    ?: return bail("没有 stco/co64")
            trace("parse: stbl ok codec=$codec stsz=${stsz.start}..${stsz.end} stco=${stco.start}..${stco.end}")
            val stss = find(stbl.payload, stbl.end, "stss")
            val ctts = find(stbl.payload, stbl.end, "ctts")

            val sampleCount = u32(d, stsz.payload + 8).toInt()
            if (sampleCount <= 0 || sampleCount > 2_000_000) return bail("采样数异常 $sampleCount")

            val sizes = IntArray(sampleCount)
            val fixedSize = u32(d, stsz.payload + 4).toInt()
            if (fixedSize > 0) {
                sizes.fill(fixedSize)
            } else {
                if (stsz.payload + 12 + sampleCount * 4 > stsz.end) return bail("stsz 大小表越界")
                for (i in 0 until sampleCount) sizes[i] = u32(d, stsz.payload + 12 + i * 4).toInt()
            }

            // 解码时间戳（stts 条目原样留存，改写时要用）
            val entryCount = u32(d, stts.payload + 4).toInt()
            if (entryCount <= 0 || stts.payload + 8 + entryCount * 8 > stts.end) return bail("stts 条目越界 n=$entryCount")
            val sttsCnt = IntArray(entryCount)
            val sttsDelta = LongArray(entryCount)
            val ticks = LongArray(sampleCount)
            var idx = 0
            var dts = 0L
            for (e in 0 until entryCount) {
                val cnt = u32(d, stts.payload + 8 + e * 8).toInt()
                val delta = u32(d, stts.payload + 12 + e * 8)
                sttsCnt[e] = cnt
                sttsDelta[e] = delta
                var j = 0
                while (j < cnt && idx < sampleCount) {
                    ticks[idx] = dts
                    dts += delta
                    idx++
                    j++
                }
                if (idx >= sampleCount) break
            }
            if (idx < sampleCount) return bail("stts 覆盖不全 $idx/$sampleCount")

            // ctts（展示时间偏移，B 帧文件才有）
            var cttsCnt = IntArray(0)
            if (ctts != null) {
                val cver = d[ctts.payload].toInt() and 0xFF
                val ce = u32(d, ctts.payload + 4).toInt()
                if (ce > 0 && ctts.payload + 8 + ce * 8 <= ctts.end) {
                    cttsCnt = IntArray(ce)
                    var i2 = 0
                    for (e in 0 until ce) {
                        val cnt = u32(d, ctts.payload + 8 + e * 8).toInt()
                        var off = u32(d, ctts.payload + 12 + e * 8)
                        if (cver == 1 && off > Int.MAX_VALUE) off -= 4294967296L
                        cttsCnt[e] = cnt
                        var j = 0
                        while (j < cnt && i2 < sampleCount) {
                            ticks[i2] += off
                            i2++
                            j++
                        }
                        if (i2 >= sampleCount) break
                    }
                }
            }

            val times = LongArray(sampleCount)
            for (i in 0 until sampleCount) times[i] = ticks[i] * 1_000_000L / timescale

            // 采样点 → 文件字节位置：stsc 说每个 chunk 装几个采样点，stco/co64 给 chunk 偏移
            val scCount = u32(d, stsc.payload + 4).toInt()
            if (scCount <= 0 || stsc.payload + 8 + scCount * 12 > stsc.end) return bail("stsc 条目越界 n=$scCount")
            val stscFirst = IntArray(scCount)
            val stscSpc = IntArray(scCount)
            for (e in 0 until scCount) {
                stscFirst[e] = u32(d, stsc.payload + 8 + e * 12).toInt()
                stscSpc[e] = u32(d, stsc.payload + 12 + e * 12).toInt()
            }
            val chunkCount = u32(d, stco.payload + 4).toInt()
            if (chunkCount <= 0 || chunkCount > 4_000_000) return bail("chunk 数异常 $chunkCount")
            val avail = stco.end - stco.payload - 8
            val stcoWide = avail >= chunkCount * 8
            val per = if (stcoWide) 8 else 4
            if (avail < chunkCount * per) return bail("stco 表长度不足 avail=$avail n=$chunkCount per=$per")
            if (stco.payload + 8 + chunkCount * per > stco.end) return bail("stco 越界")
            val chunkOff = LongArray(chunkCount)
            for (i in 0 until chunkCount) {
                chunkOff[i] = if (stcoWide) u64(d, stco.payload + 8 + i * 8)
                else u32(d, stco.payload + 8 + i * 4)
            }

            val offsets = IntArray(sampleCount)
            val chunkOf = IntArray(sampleCount)
            val chunkFirst = IntArray(chunkCount)
            var si = 0
            var e = 0
            for (c in 0 until chunkCount) {
                while (e + 1 < scCount && stscFirst[e + 1] <= c + 1) e++
                chunkFirst[c] = si
                var off = chunkOff[c]
                var j = 0
                while (j < stscSpc[e] && si < sampleCount) {
                    if (off > Int.MAX_VALUE) return bail("偏移超过 2G")
                    offsets[si] = off.toInt()
                    chunkOf[si] = c
                    off += sizes[si]
                    si++
                    j++
                }
                if (si >= sampleCount) break
            }
            if (si < sampleCount) return bail("chunk 表覆盖不全 $si/$sampleCount")

            // 关键帧表（stss 缺省表示全是关键帧）
            val sync = BooleanArray(sampleCount)
            if (stss == null) {
                sync.fill(true)
            } else {
                val cnt = u32(d, stss.payload + 4).toInt()
                if (cnt > 0 && stss.payload + 8 + cnt * 4 <= stss.end) {
                    for (i in 0 until cnt) {
                        val num = u32(d, stss.payload + 8 + i * 4).toInt() - 1      // 1-based
                        if (num in 0 until sampleCount) sync[num] = true
                    }
                }
            }

            trace("parse: ok type=$type codec=$codec ts=$timescale n=$sampleCount chunks=$chunkCount wide=$stcoWide")
            return Track(
                type, codec, timescale, times, offsets, sizes, sync,
                sttsCnt, sttsDelta, cttsCnt, fixedSize, stscFirst, stscSpc, chunkFirst, chunkOf, stcoWide,
                stts, stsz, stsc, stco, stss, ctts, mdhdDur, tkhdDur, elst
            )
        }

        /** 在 [start, end) 里找 type 盒子（只看直接子盒） */
        private fun find(start: Int, end: Int, type: String): Box? {
            var p = start
            while (p + 8 <= end) {
                val size = boxSize(p, end) ?: return null
                if (fourcc(p + 4) == type) return Box(p, p + headerLen(p), p + size)
                p += size
            }
            return null
        }

        private fun headerLen(p: Int): Int = if (u32(d, p) == 1L) 16 else 8

        private fun boxSize(p: Int, end: Int): Int? {
            var size = u32(d, p)
            var head = 8
            if (size == 1L) {
                if (p + 16 > end) return null
                size = u64(d, p + 8)
                head = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < head || p + size > end) return null
            return size.toInt()
        }

        private fun fourcc(p: Int): String = String(d, p, 4, Charsets.US_ASCII)

        private fun u32(b: ByteArray, p: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (b[p + i].toInt() and 0xFF).toLong()
            return v
        }

        private fun u64(b: ByteArray, p: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[p + i].toInt() and 0xFF).toLong()
            return v
        }
    }
}
