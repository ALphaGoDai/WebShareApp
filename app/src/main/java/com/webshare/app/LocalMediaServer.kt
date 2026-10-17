package com.webshare.app

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID

/**
 * 缓存视频的离线播放：只听 127.0.0.1 的小 HTTP 服务。
 *
 * 为什么不用 file:// —— WebView 里跨目录的 file 地址容易踩 allowFileAccessFromFileURLs 的那堆坑，
 * 而且没有 Range 就没法拖动进度条。这里按标准的 Range/206 实现，路径里带一次性 token
 * （同机别的 App 猜不到），播放页也由它一起发出去，页面与视频同源。
 */
object LocalMediaServer {

    private const val TAG = "LocalMediaServer"

    private var server: ServerSocket? = null
    private var port = 0
    private val token: String = UUID.randomUUID().toString().replace("-", "").take(12)

    /** 连接线程拿不到 Context，起服务时先记住一个 applicationContext */
    private var appContext: Context? = null

    private fun Socket.ctx(): Context = appContext ?: throw IOException("没有 Context")

    /** 起服务（幂等），返回端口；起不来返回 -1 */
    @Synchronized
    fun ensureStarted(ctx: Context): Int {
        appContext = ctx.applicationContext
        server?.let { if (!it.isClosed) return port }
        return try {
            val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            server = s
            port = s.localPort
            Thread({
                while (!s.isClosed) {
                    val client = try {
                        s.accept()
                    } catch (e: Exception) {
                        break
                    }
                    Thread { handleSafely(client) }.apply { isDaemon = true }.start()
                }
            }, "local-media-accept").apply { isDaemon = true }.start()
            Log.i(TAG, "离线播放服务已启动：http://127.0.0.1:$port")
            port
        } catch (e: Exception) {
            Log.w(TAG, "起离线播放服务失败: ${e.message}")
            -1
        }
    }

    /** 某个缓存视频的播放页地址（先确保服务起来了） */
    fun playerUrl(ctx: Context, entry: VideoCacheStore.Entry): String? {
        val p = ensureStarted(ctx)
        if (p <= 0) return null
        return "http://127.0.0.1:$p/p/$token/${entry.id}"
    }

    /** 视频文件本身的地址（「存到手机」时把这份本机缓存写进系统下载目录） */
    fun fileUrl(ctx: Context, entry: VideoCacheStore.Entry): String? {
        val p = ensureStarted(ctx)
        if (p <= 0) return null
        return "http://127.0.0.1:$p/v/$token/${entry.id}"
    }

    private fun handleSafely(client: Socket) {
        try {
            client.soTimeout = 15000
            handle(client)
        } catch (e: Exception) {
            Log.w(TAG, "连接处理失败: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun handle(client: Socket) {
        val input = client.getInputStream()
        val head = readHead(input) ?: return
        val line = head.lineSequence().firstOrNull() ?: return
        val parts = line.split(' ')
        if (parts.size < 2 || !parts[0].equals("GET", true)) {
            respondError(client, 405, "只支持 GET")
            return
        }
        val range = head.lineSequence()
            .firstOrNull { it.startsWith("Range:", true) }
            ?.substringAfter("bytes=")?.trim()
        val rawPath = parts[1]
        val path = URLDecoder.decode(rawPath, "UTF-8")

        // /p/<token>/<id> 播放页； /v/<token>/<id> 视频文件
        val segs = path.trim('/').split('/')
        if (segs.size != 3 || segs[1] != token) {
            respondError(client, 404, "没有这个地址")
            return
        }
        val id = segs[2].substringBefore('?').toLongOrNull()
        if (id == null) {
            respondError(client, 404, "没有这个地址")
            return
        }
        val entry = VideoCacheStore.find(client.ctx(), id)
        if (entry == null) {
            respondError(client, 404, "缓存已经删掉了")
            return
        }
        val file = VideoCacheStore.fileFor(client.ctx(), entry)
        if (!file.exists()) {
            respondError(client, 404, "缓存文件不见了")
            return
        }
        when (segs[0]) {
            "p" -> respondHtml(client, playerPage(entry))
            "v" -> respondFile(client, file, contentType(file), range)
            else -> respondError(client, 404, "没有这个地址")
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        var last4 = 0
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) return sb.toString()   // \r\n\r\n
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun respondHtml(client: Socket, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
        val out = BufferedOutputStream(client.getOutputStream())
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun respondError(client: Socket, code: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        val out = BufferedOutputStream(client.getOutputStream())
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    /** 文件响应：支持 Range（拖动进度条），不支持时整份发 */
    private fun respondFile(client: Socket, file: File, mime: String, range: String?) {
        val total = file.length()
        var start = 0L
        var end = total - 1
        var partial = false
        if (!range.isNullOrEmpty()) {
            val m = Regex("(\\d*)-(\\d*)").find(range.trim())
            if (m != null) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                if (a.isNotEmpty()) start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = b.toLongOrNull() ?: end
                if (start > end || start >= total) {
                    respondError(client, 416, "范围不对")
                    return
                }
                end = minOf(end, total - 1)
                partial = true
            }
        }
        val len = end - start + 1
        val head = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: $len\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (partial) append("Content-Range: bytes $start-$end/$total\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
        out.write(head.toByteArray(Charsets.US_ASCII))
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    private fun contentType(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun playerPage(entry: VideoCacheStore.Entry): String {
        val title = esc(entry.title.ifBlank { "离线视频" })
        val url = "/v/$token/${entry.id}"
        return """<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>$title · 离线视频</title>
<style>
  html,body{margin:0;padding:0;background:#0b0c0e;color:#e8eaed;
            font-family:-apple-system,"HarmonyOS Sans","Noto Sans CJK SC",sans-serif}
  .head{padding:12px 14px 8px;font-size:15px;font-weight:600;line-height:1.4}
  .meta{padding:0 14px 10px;color:#98a0ad;font-size:12px}
  video{width:100%;background:#000;display:block;max-height:78vh}
  .note{padding:12px 14px 20px;color:#8b93a1;font-size:12px;line-height:1.7}
  .src{word-break:break-all;color:#6f7784}
  .hint{position:fixed;left:0;right:0;bottom:22px;z-index:4;text-align:center;
        color:#d9dce2;font-size:14px;text-shadow:0 1px 5px #000;
        pointer-events:none;opacity:0;transition:opacity .35s}
  .hud{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);z-index:5;
       color:#fff;background:rgba(0,0,0,.62);padding:10px 22px;border-radius:999px;
       font-size:20px;opacity:0;transition:opacity .18s;pointer-events:none}
  /* 电视/大屏：视频铺满整屏，标题和提示浮在上面 */
  @media (min-width:700px){
    html,body{height:100%;overflow:hidden;background:#000}
    .head{position:fixed;left:24px;right:24px;top:14px;z-index:3;padding:0;
          font-size:22px;text-shadow:0 2px 10px #000}
    .meta{position:fixed;left:24px;right:24px;top:52px;z-index:3;padding:0;
          font-size:14px;text-shadow:0 1px 6px #000}
    video{position:fixed;left:0;top:0;width:100vw;height:100vh;max-height:none;
          object-fit:contain}
    .note{display:none}
  }
</style></head>
<body>
  <div class="head">$title</div>
  <div class="meta">${DownloadService.fmtSize(entry.size)} · 已缓存到浏览器，不联网也能看</div>
  <video src="$url" controls autoplay playsinline preload="auto"></video>
  <div class="note">这份视频存在 App 自己的目录里，在「已下载 → 缓存视频」里可以保存到手机或删掉。<br>
    来源：<span class="src">${esc(entry.pageUrl)}</span></div>
  <div class="hint" id="hint">遥控器：←/→ 快退快进 10 秒 · OK 暂停/继续 · ↑/↓ 音量</div>
  <div class="hud" id="hud"></div>
<script>
(function(){
  var v = document.querySelector('video');
  var hud = document.getElementById('hud');
  var hint = document.getElementById('hint');
  var hideTimer = null, hudTimer = null;

  function flash(text) {
    hud.textContent = text;
    hud.style.opacity = '1';
    clearTimeout(hudTimer);
    hudTimer = setTimeout(function(){ hud.style.opacity = '0'; }, 700);
  }
  function showHint() {
    hint.style.opacity = '1';
    clearTimeout(hideTimer);
    hideTimer = setTimeout(function(){ hint.style.opacity = '0'; }, 4500);
  }
  function play() {
    var p = v.play();
    if (p && p.catch) p.catch(function(){});
  }
  function toggle() {
    if (v.paused) { play(); flash('播放'); } else { v.pause(); flash('暂停'); }
  }
  function seek(d) {
    var max = isFinite(v.duration) ? v.duration : 1e9;
    var t = Math.max(0, Math.min(max, (v.currentTime || 0) + d));
    v.currentTime = t;
    flash((d > 0 ? '+' : '') + d + ' 秒');
  }
  window.addEventListener('keydown', function(e){
    var k = e.keyCode, used = true;
    if (k === 37 || k === 227) seek(-10);                 // 左 / 快退
    else if (k === 39 || k === 228) seek(10);             // 右 / 快进
    else if (k === 38) {                                  // 上：音量 +
      v.volume = Math.min(1, (v.volume || 0) + 0.1);
      flash('音量 ' + Math.round(v.volume * 100) + '%');
    } else if (k === 40) {                                // 下：音量 -
      v.volume = Math.max(0, (v.volume || 0) - 0.1);
      flash('音量 ' + Math.round(v.volume * 100) + '%');
    } else if (k === 13 || k === 23 || k === 66 || k === 32 || k === 85 || k === 179) {
      toggle();                                           // OK / 空格 / 播放暂停
    } else {
      used = false;
    }
    if (used) { e.preventDefault(); e.stopPropagation(); }
    showHint();
  }, true);

  // 让页面本身拿得到按键（电视上 WebView 把按键交给当前焦点元素）
  document.body.tabIndex = 0;
  try { document.body.focus(); } catch (e) {}
  document.addEventListener('click', function(){ try { document.body.focus(); } catch (e) {} });
  v.addEventListener('loadedmetadata', showHint);
  v.addEventListener('error', function(){ flash('这个视频解不开'); });
  showHint();
})();
</script>
</body></html>"""
    }
}
