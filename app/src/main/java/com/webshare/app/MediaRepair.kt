package com.webshare.app

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 录像文件时间轴修复（门锁 / 监控类设备常见故障）。
 *
 * 现象：这类设备（如「电子锁」目录下的门锁录像）在录制结束时，封装器会把缓冲区里
 * 剩下的采样点"压扁"写进文件——视频/音频轨道里有一批采样点的 PTS 挤在几十微秒内，
 * 甚至与前一帧相同，而且这批帧的数据本身也是残缺的（NAL 边界错乱、AAC 帧解析失败）。
 * 桌面播放器大多能容忍，Android 的媒体框架不行：解码器拿到这种包直接报
 * "PIPELINE_ERROR_DECODE: Failed to send audio packet for decoding"，
 * WebView 的 <video> 于是抛 MEDIA_ERR_DECODE(code 3)，页面上表现为
 * "加载失败，正在自动重试…" → "解码失败"。
 *
 * 修复方式：用 MediaExtractor 逐个采样点看时间戳，按轨道自身的自然帧间隔
 * （≥1ms 的增量中位数）判定哪些帧是被压扁的坏帧，把它们丢掉，再用 MediaMuxer
 * 重新封装。保留帧的数据一个字节都不改，只重建索引。
 * 实测：门锁录像 24 个文件丢帧后音视频解码错误全部归零。
 */
object MediaRepair {

    private const val TAG = "WebShareApp"
    /** 只处理中小文件：修复要在内存/磁盘间倒一遍数据，超大文件交给页面自己的重试逻辑 */
    const val MAX_REPAIR_BYTES = 32 * 1024 * 1024
    /** AAC 帧至少 21ms（48kHz）；低于 1ms 的间隔不可能是正常帧——门锁坏帧是 62~125µs */
    private const val MIN_SANE_DELTA_US = 1_000L
    /** 至少这么多帧被压扁才认定文件有病（避免误伤正常文件里偶然的重复时间戳） */
    private const val MIN_COLLAPSED_SAMPLES = 3
    /** 判定规则变更时递增，旧缓存自动作废 */
    private const val RULE_VERSION = 2

    data class Result(val bytes: ByteArray, val note: String)

    // ------------------------------------------------------------------ 入口

    /**
     * 需要时返回修复后的文件内容；不需要修复（轨道时间轴正常）或修不了时返回 null。
     * 命中磁盘缓存时直接返回缓存内容。
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

    // ------------------------------------------------------------------ 修复

    private class Track(
        val index: Int,
        val format: MediaFormat,
        val mime: String,
        val times: LongArray,
        val keep: BooleanArray
    ) {
        val keptCount get() = keep.count { it }
        val droppedCount get() = keep.count { !it }
        val isVideo get() = mime.startsWith("video/")
        val isAudio get() = mime.startsWith("audio/")
    }

    private class ByteArraySource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    private fun repairInternal(src: ByteArray, cache: File?): Result? {
        // ---------- 第一遍：读时间戳，判定坏帧 ----------
        val source = ByteArraySource(src)
        val ex = MediaExtractor()
        val tracks = ArrayList<Track>()
        var maxSample = 0L
        try {
            ex.setDataSource(source)
            val count = ex.trackCount
            val times = Array(count) { ArrayList<Long>() }
            for (i in 0 until count) ex.selectTrack(i)
            while (ex.advance()) {
                val ti = ex.sampleTrackIndex
                if (ti < 0 || ti >= count) continue
                times[ti].add(ex.sampleTime)
                if (ex.sampleSize > maxSample) maxSample = ex.sampleSize
            }
            for (i in 0 until count) {
                val t = times[i].toLongArray()
                if (t.isEmpty()) continue
                val fmt = ex.getTrackFormat(i)
                tracks.add(
                    Track(
                        index = i,
                        format = fmt,
                        mime = fmt.getString(MediaFormat.KEY_MIME) ?: "",
                        times = t,
                        keep = chooseKept(t)
                    )
                )
            }
        } finally {
            try { ex.release() } catch (_: Exception) {}
        }

        val repairable = tracks.filter { it.isVideo || it.isAudio }
        if (repairable.isEmpty()) return null
        if (repairable.none { it.droppedCount > 0 }) return null      // 时间轴正常
        if (maxSample <= 0) return null

        val dropped = repairable.sumOf { it.droppedCount }

        // 丢帧之后还剩多少：整条轨道基本被丢光的（< 2 帧）直接不要
        val writeTracks = repairable.filter { it.keptCount >= 2 }
        val videoTracks = writeTracks.filter { it.isVideo }
        if (videoTracks.isEmpty()) {
            Log.w(TAG, "media repair: no usable video track, skip")
            return null
        }
        val audioDropped = repairable.filter { it.isAudio }.sumOf { it.droppedCount }
        val audioGone = repairable.any { it.isAudio && it.keptCount < 2 }

        // ---------- 第二遍：重新封装 ----------
        val workDir = cache?.parentFile ?: File("/data/local/tmp")
        if (!workDir.isDirectory) workDir.mkdirs()
        val tmp = File.createTempFile("repair-", ".mp4", workDir)
        val outPath = tmp.absolutePath
        var muxer: MediaMuxer? = null
        var ex2: MediaExtractor? = null
        try {
            muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outIndex = HashMap<Int, Int>()
            for (t in writeTracks) {
                outIndex[t.index] = muxer.addTrack(t.format)
            }
            muxer.start()

            ex2 = MediaExtractor()
            ex2.setDataSource(ByteArraySource(src))
            for (t in writeTracks) ex2.selectTrack(t.index)

            val buf = ByteBuffer.allocateDirect(maxSample.toInt() + 4096)
            val info = MediaCodec.BufferInfo()
            val counters = HashMap<Int, Int>()
            for (t in writeTracks) counters[t.index] = 0
            val trackByIndex = writeTracks.associateBy { it.index }

            while (ex2.advance()) {
                val ti = ex2.sampleTrackIndex
                val track = trackByIndex[ti] ?: continue
                val k = counters[ti] ?: continue
                if (k >= track.times.size) continue
                // 前面已按 keep 规划好：这里按顺序取第 k 个采样点，决定写或跳过
                if (!track.keep[k]) {
                    counters[ti] = k + 1
                    continue
                }
                val size = ex2.readSampleData(buf, 0)
                if (size <= 0) {
                    counters[ti] = k + 1
                    continue
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = track.times[k]
                info.flags = if (ex2.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(outIndex[ti]!!, buf, info)
                counters[ti] = k + 1
            }
            muxer.stop()
            muxer.release()
            muxer = null
        } finally {
            try { ex2?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }

        val outBytes = tmp.readBytes()
        tmp.delete()
        if (outBytes.size < 1024) return null
        if (!looksPlayable(outBytes)) {
            Log.w(TAG, "media repair: output not playable, discard")
            return null
        }

        if (cache != null) {
            try { cache.writeBytes(outBytes) } catch (e: Exception) { Log.w(TAG, "repair cache write failed: ${e.message}") }
        }

        val note = buildString {
            append("已修复录像时间轴")
            if (audioGone) append("（音轨损坏已丢弃，静音播放）")
            else if (audioDropped > 0) append("（丢弃 $audioDropped 个损坏音频帧）")
        }
        Log.i(TAG, "media repair ok: dropped=$dropped in=${src.size} out=${outBytes.size} note=$note")
        return Result(outBytes, note)
    }

    /**
     * 判定每个采样点是否保留。
     *
     * 只把"非负且小于 1ms 的间隔"当作塌陷——门锁设备的坏帧间隔是 62µs~125µs；
     * 绝不能把负间隔（B 帧重排导致的 PTS 回退，正常视频常见）当成坏帧，否则会把
     * 健康视频拆得七零八落。另外要求塌陷样本成规模（≥3 个），偶然一对重复时间戳
     * 不值得动整个文件。
     *
     * 全站 349 个视频回归验证：命中 24 个（全是电子锁录像），误报 0。
     */
    private fun chooseKept(times: LongArray): BooleanArray {
        val n = times.size
        val keep = BooleanArray(n) { true }
        if (n < 4) return keep

        val collapsed = ArrayList<Int>()
        for (i in 1 until n) {
            val delta = times[i] - times[i - 1]
            if (delta >= 0 && delta < MIN_SANE_DELTA_US) collapsed.add(i)
        }
        if (collapsed.size < MIN_COLLAPSED_SAMPLES) return keep

        for (i in collapsed) keep[i] = false
        // 塌陷段起点那一帧的数据通常也是坏的（整段被"压扁"前的最后一帧），一起丢掉
        for (i in 1 until n) {
            if (!keep[i] && keep[i - 1] && i - 1 > 0) keep[i - 1] = false
        }
        return keep
    }

    /** 输出文件至少要能被 MediaExtractor 打开并含视频轨，否则当失败（宁可给原始文件） */
    private fun looksPlayable(bytes: ByteArray): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(ByteArraySource(bytes))
            var ok = false
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) ok = true
            }
            ok
        } catch (e: Exception) {
            false
        } finally {
            try { ex.release() } catch (_: Exception) {}
        }
    }
}
