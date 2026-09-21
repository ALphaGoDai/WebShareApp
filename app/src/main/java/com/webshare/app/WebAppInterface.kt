package com.webshare.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class WebAppInterface(private val context: Context) {

    @Volatile
    private var sharedText: String = ""

    @Volatile
    private var sharedType: String = "none"

    @Volatile
    private var webViewRef: WebView? = null

    @Volatile
    private var dohEnabled: Boolean = false

    @Volatile
    private var dohUrl: String = ""

    @Volatile
    private var cachedResolver: DohDnsResolver? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val executor: ExecutorService = Executors.newCachedThreadPool()

    /** 用户在系统文件选择器里选过的文件：文件名(+大小) -> content uri，供 multipart 上传取内容 */
    private val pickedFiles = ConcurrentHashMap<String, Uri>()

    fun setSharedContent(text: String, type: String) {
        sharedText = text
        sharedType = type
    }

    fun clearSharedContent() {
        sharedText = ""
        sharedType = "none"
    }

    fun attach(webView: WebView, dohEnabled: Boolean, dohUrl: String) {
        webViewRef = webView
        this.dohEnabled = dohEnabled
        this.dohUrl = dohUrl
        cachedResolver = if (dohEnabled && dohUrl.isNotEmpty()) {
            try { DohDnsResolver(dohUrl) } catch (e: Exception) { null }
        } else null
    }

    @JavascriptInterface
    fun getSharedText(): String = sharedText

    @JavascriptInterface
    fun getSharedType(): String = sharedType

    @JavascriptInterface
    fun getSettings(): String {
        val settings = SettingsManager(context)
        return JSONObject().apply {
            put("url", settings.url)
            put("dohEnabled", settings.dohEnabled)
            put("dohUrl", settings.dohUrl)
        }.toString()
    }

    /**
     * POST 请求（走 App 的自定义 DNS + TLS，绕过系统 DNS 劫持）。
     * 异步回调，callback 为全局函数名:
     *   Android.httpPost("https://site/api", "a=1&b=2",
     *                   "application/x-www-form-urlencoded", "onPostDone")
     *   function onPostDone(r){ r = {ok, status, data, contentType, error} }
     * callback 传空字符串则调用 window.onHttpPostResult。
     */
    @JavascriptInterface
    fun httpPost(url: String, body: String, contentType: String, callback: String) {
        enqueue(url, "POST", body, contentType, callback, "")
    }

    /** 带自定义请求头版本：headersJson 形如 {"Authorization":"Bearer x"} */
    @JavascriptInterface
    fun httpPostH(url: String, body: String, contentType: String, callback: String, headersJson: String) {
        enqueue(url, "POST", body, contentType, callback, headersJson)
    }

    /** GET 版本，用法同 httpPost（body/contentType 传空字符串）。 */
    @JavascriptInterface
    fun httpGet(url: String, callback: String) {
        enqueue(url, "GET", "", "", callback, "")
    }

    @JavascriptInterface
    fun httpGetH(url: String, callback: String, headersJson: String) {
        enqueue(url, "GET", "", "", callback, headersJson)
    }

    /**
     * 带文件的表单上传（网页里的 <input type="file"> + FormData）。
     * partsJson: {"fields":[{"name":..,"value":..}],"files":[{"name":..,"filename":..,"size":..}]}
     * 文件内容按「文件名+大小」到用户刚选过的文件里找，全程流式读取，不经过 base64。
     */
    @JavascriptInterface
    fun httpPostForm(url: String, partsJson: String, callback: String) {
        enqueueTask(callback) { executeMultipart(url, partsJson) }
    }

    /**
     * 本机解码能力（JSON）。WebView 的 canPlayType 在部分 ROM 上会谎报支持 H.265，
     * 网页据此选码流/决定要不要服务端转码时会被带偏，注入脚本会用这里的真实能力纠正。
     */
    @JavascriptInterface
    fun mediaCaps(): String = MediaCaps.json()

    /** 读剪贴板文本（非安全上下文里网页拿不到 navigator.clipboard，只能走桥接） */
    @JavascriptInterface
    fun readClipboard(): String {        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount <= 0) return ""
            clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 写剪贴板文本，返回是否成功 */
    @JavascriptInterface
    fun copyText(text: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            mainHandler.post {
                try {
                    cm.setPrimaryClip(ClipData.newPlainText("网页文本", text))
                } catch (_: Exception) {}
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 网页里的 window.open / target=_blank：用系统浏览器打开（不占本应用窗口） */
    @JavascriptInterface
    fun openExternal(url: String): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 记录用户通过系统文件选择器选中的文件。
     * 网页里 File 对象的 name/size 在 multipart 上传时用来把内容找回来。
     */
    fun registerPickedFiles(uris: List<Uri>) {
        pickedFiles.clear()
        for (uri in uris) {
            try {
                var name: String? = null
                var size = -1L
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
                val fileName = name ?: uri.lastPathSegment ?: continue
                pickedFiles[fileKey(fileName, size)] = uri
                pickedFiles[fileKey(fileName, -1L)] = uri
                pickedFiles[fileName.lowercase()] = uri
                Log.d(TAG, "picked file registered: name=$fileName size=$size uri=$uri")
            } catch (e: Exception) {
                Log.w(TAG, "failed to register picked file $uri", e)
            }
        }
    }

    private fun fileKey(name: String, size: Long) = name.lowercase() + "|" + size

    private fun findPickedFile(name: String, size: Long): Uri? {
        return pickedFiles[fileKey(name, size)]
            ?: pickedFiles[fileKey(name, -1L)]
            ?: pickedFiles[name.lowercase()]
    }

    private fun enqueueTask(callback: String, task: () -> String) {
        executor.execute {
            val result = try {
                task()
            } catch (e: Exception) {
                failResult(e.message ?: "未知错误")
            }
            deliver(callback.ifEmpty { "window.onHttpPostResult" }, result)
        }
    }

    private fun enqueue(
        url: String,
        method: String,
        body: String,
        contentType: String,
        callback: String,
        headersJson: String
    ) {
        executor.execute {
            val result = try {
                executeRequest(url, method, body, contentType, headersJson)
            } catch (e: Exception) {
                failResult(e.message ?: "未知错误")
            }
            deliver(callback.ifEmpty { "window.onHttpPostResult" }, result)
        }
    }

    private fun okResult(status: Int, data: String, contentType: String): String {
        return JSONObject().apply {
            put("ok", true)
            put("status", status)
            put("data", data)
            put("contentType", contentType)
            put("error", JSONObject.NULL)
        }.toString()
    }

    private fun failResult(message: String): String {
        return JSONObject().apply {
            put("ok", false)
            put("status", 0)
            put("data", "")
            put("error", message)
        }.toString()
    }

    private fun deliver(callback: String, jsonResult: String) {
        val safeName = callback.removePrefix("window.").replace(Regex("[^A-Za-z0-9_$.]"), "")
        if (safeName.isEmpty()) return
        val js = "try{(typeof $safeName==='function')&&" +
            "$safeName($jsonResult);}catch(e){console.error('httpPost callback error:',e);}"
        mainHandler.post {
            webViewRef?.evaluateJavascript(js, null)
        }
    }

    private fun resolveHost(host: String): String {
        val r = cachedResolver ?: return host
        return try {
            r.lookup(host).firstOrNull()?.hostAddress ?: host
        } catch (e: Exception) {
            host
        }
    }

    private fun executeRequest(
        url: String,
        method: String,
        body: String,
        contentType: String,
        headersJson: String
    ): String {
        val extraHeaders = parseHeaders(headersJson)
        return try {
            executeWithOkHttp(url, method, body, contentType, extraHeaders)
        } catch (e: Exception) {
            // OkHttp strict parsing failed; retry over raw socket with the
            // lenient parser (plain first, then TLS).
            try {
                executeWithRawSocket(url, method, body, contentType, false, extraHeaders)
            } catch (e2: Exception) {
                executeWithRawSocket(url, method, body, contentType, true, extraHeaders)
            }
        }
    }

    private fun parseHeaders(json: String): Map<String, String> {
        if (json.isEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            for (key in obj.keys()) {
                val skip = key.equals("Content-Type", true) || key.equals("Cookie", true) ||
                    key.equals("Content-Length", true) || key.equals("Host", true) ||
                    key.equals("Connection", true) || key.equals("Accept-Encoding", true)
                if (!skip) map[key] = obj.getString(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun buildClient(): OkHttpClient {
        val cb = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
        cachedResolver?.let { cb.dns(it) }
        // Share the WebView cookie store: cookies set by bridge POSTs become
        // visible to subsequent page navigations (login session fix).
        cb.cookieJar(WebCookieJar())
        return cb.build()
    }

    private fun executeWithOkHttp(
        url: String,
        method: String,
        body: String,
        contentType: String,
        extraHeaders: Map<String, String>
    ): String {
        val builder = Request.Builder().url(url)
        if (method == "POST") {
            val ct = if (contentType.isEmpty()) "application/x-www-form-urlencoded" else contentType
            val reqBody = (body.ifEmpty { "" }).toRequestBody(ct.toMediaType())
            builder.post(reqBody)
        } else {
            builder.get()
        }
        for ((k, v) in extraHeaders) builder.header(k, v)
        builder.header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        buildClient().newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            return okResult(resp.code, text, resp.header("Content-Type") ?: "")
        }
    }

    /**
     * 带文件的表单上传。文件内容从用户刚选中的 content uri 流式读出（不经过 base64），
     * 请求本身依然走 App 的 DoH + TLS 通道。
     */
    private fun executeMultipart(url: String, partsJson: String): String {
        val obj = try {
            JSONObject(partsJson.ifEmpty { "{}" })
        } catch (e: Exception) {
            JSONObject()
        }

        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)

        obj.optJSONArray("fields")?.let { fields ->
            for (i in 0 until fields.length()) {
                val f = fields.optJSONObject(i) ?: continue
                builder.addFormDataPart(f.optString("name"), f.optString("value"))
            }
        }

        var fileCount = 0
        obj.optJSONArray("files")?.let { files ->
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val field = f.optString("name")
                val fileName = f.optString("filename")
                val size = f.optLong("size", -1L)
                val uri = findPickedFile(fileName, size)
                    ?: throw IOException("文件「$fileName」已失效，请重新选择文件")
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                builder.addFormDataPart(
                    field,
                    fileName,
                    ContentUriRequestBody(context.contentResolver, uri, mime)
                )
                Log.d(TAG, "upload part: field=$field name=$fileName uri=$uri")
                fileCount++
            }
        }

        if (fileCount == 0) throw IOException("没有可上传的文件")

        val body = builder.build()
        val headers = linkedMapOf<String, String>()
        obj.optJSONObject("headers")?.let { h ->
            for (key in h.keys()) {
                val skip = key.equals("Content-Type", true) || key.equals("Cookie", true) ||
                    key.equals("Content-Length", true) || key.equals("Host", true) ||
                    key.equals("Connection", true) || key.equals("Accept-Encoding", true)
                if (!skip) headers[key] = h.optString(key)
            }
        }
        if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = UA

        // 网页可能是 http:// 源(被 WebView 降级过)，而站点实际只开 TLS：
        // 明文 POST 会被服务器重置，所以失败后换协议再试一次，和 GET 通道一致。
        val candidates = listOfNotNull(url, swapScheme(url))
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                return postMultipart(candidate, body, headers)
            } catch (e: Exception) {
                Log.w(TAG, "multipart post failed: $candidate (${e.message})")
                lastError = e
            }
        }
        throw (lastError ?: IOException("上传失败"))
    }

    private fun postMultipart(
        url: String,
        body: MultipartBody,
        headers: Map<String, String>
    ): String {
        val builder = Request.Builder().url(url).post(body)
        for ((k, v) in headers) builder.header(k, v)
        buildClient().newCall(builder.build()).execute().use { resp ->
            return okResult(resp.code, resp.body?.string() ?: "", resp.header("Content-Type") ?: "")
        }
    }

    private fun swapScheme(url: String): String? = when {
        url.startsWith("https://", true) -> url.replaceFirst("https://", "http://", true)
        url.startsWith("http://", true) -> url.replaceFirst("http://", "https://", true)
        else -> null
    }

    private fun executeWithRawSocket(
        url: String,
        method: String,
        body: String,
        contentType: String,
        useTls: Boolean,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val parsed = URL(url)
        val host = parsed.host
        val port = parsed.port.takeIf { it > 0 } ?: parsed.defaultPort
        val path = (parsed.path.ifEmpty { "/" }) +
            (parsed.query?.let { "?$it" } ?: "")
        val ip = resolveHost(host)

        var socket: Socket? = null
        try {
            val raw = Socket()
            socket = raw
            raw.connect(InetSocketAddress(ip, port), 15000)
            raw.soTimeout = 15000

            val target: Socket = if (useTls || parsed.protocol.equals("https", true)) {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(host))
                ssl.sslParameters = params
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                    throw java.io.IOException("TLS证书主机名不匹配: $host")
                }
                socket = ssl
                ssl
            } else {
                raw
            }

            val hostHeader = if (port != 80 && port != 443) "$host:$port" else host
            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(hostHeader).append("\r\n")
                .append("User-Agent: Mozilla/5.0 (Linux; Android 12) ")
                .append("AppleWebKit/537.36 (KHTML, like Gecko) ")
                .append("Chrome/120.0.0.0 Mobile Safari/537.36\r\n")

            for ((k, v) in extraHeaders) sb.append(k).append(": ").append(v).append("\r\n")

            // Session cookies from the WebView cookie store (login session fix)
            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrEmpty()) {
                sb.append("Cookie: ").append(cookies).append("\r\n")
            }

            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            if (method == "POST") {
                val ct = if (contentType.isEmpty()) "application/x-www-form-urlencoded" else contentType
                sb.append("Content-Type: ").append(ct).append("\r\n")
                sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")

            val out: OutputStream = target.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            if (method == "POST" && bodyBytes.isNotEmpty()) {
                out.write(bodyBytes)
            }
            out.flush()

            val input = target.getInputStream()
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                baos.write(buf, 0, n)
            }
            val data = baos.toByteArray()
            if (data.isEmpty()) {
                throw java.io.IOException("服务器未返回任何数据")
            }
            return parseAndBuildResult(data, url)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun parseAndBuildResult(raw: ByteArray, requestUrl: String): String {
        var start = 0
        while (start < raw.size &&
            (raw[start] == 0x0d.toByte() || raw[start] == 0x0a.toByte())
        ) start++

        var headerEnd = -1
        var headerEndLen = 0
        var i = start
        while (i <= raw.size - 4) {
            if (raw[i] == 0x0d.toByte() && raw[i + 1] == 0x0a.toByte() &&
                raw[i + 2] == 0x0d.toByte() && raw[i + 3] == 0x0a.toByte()
            ) {
                headerEnd = i; headerEndLen = 4; break
            }
            if (raw[i] == 0x0a.toByte() && raw[i + 1] == 0x0a.toByte()) {
                headerEnd = i; headerEndLen = 2; break
            }
            i++
        }
        if (headerEnd < 0) throw java.io.IOException("无效的 HTTP 响应")

        val headerStr = String(raw, start, headerEnd - start, Charsets.ISO_8859_1)
        val bodyStart = minOf(headerEnd + headerEndLen, raw.size)
        var bodyBytes = raw.copyOfRange(bodyStart, raw.size)

        val lines = headerStr.split("\n").map { it.trimEnd('\r') }
        val status = lines.getOrNull(0)?.trim()?.split("\\s+".toRegex())
            ?.getOrNull(1)?.toIntOrNull() ?: 200

        var isChunked = false
        var isGzip = false
        var contentType = ""
        for (j in 1 until lines.size) {
            val line = lines[j]
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim().lowercase()
            val v = line.substring(idx + 1).trim()
            if (k == "transfer-encoding" && v.contains("chunked", true)) isChunked = true
            if (k == "content-encoding" && v.contains("gzip", true)) isGzip = true
            if (k == "content-type") contentType = v
            if (k == "set-cookie") {
                try { CookieManager.getInstance().setCookie(requestUrl, v) } catch (e: Exception) {}
            }
        }

        if (isChunked) bodyBytes = dechunk(bodyBytes)
        if (isGzip && bodyBytes.isNotEmpty()) {
            try {
                bodyBytes = java.util.zip.GZIPInputStream(ByteArrayInputStream(bodyBytes)).readBytes()
            } catch (e: Exception) {}
        }

        return okResult(status, String(bodyBytes, Charsets.UTF_8), contentType)
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        var pos = 0
        while (pos < data.size) {
            var lineEnd = pos
            while (lineEnd < data.size - 1) {
                if (data[lineEnd] == 0x0d.toByte() && data[lineEnd + 1] == 0x0a.toByte()) break
                if (data[lineEnd] == 0x0a.toByte()) break
                lineEnd++
            }
            if (lineEnd >= data.size - 1) break
            val sizeStr = String(data, pos, lineEnd - pos, Charsets.US_ASCII).trim()
            val chunkSize = sizeStr.split(";")[0].trim().toIntOrNull(16) ?: break
            if (chunkSize == 0) break
            val afterLine = if (data[lineEnd] == 0x0a.toByte()) lineEnd + 1 else lineEnd + 2
            val chunkEnd = afterLine + chunkSize
            if (chunkEnd > data.size) break
            baos.write(data, afterLine, chunkSize)
            pos = chunkEnd + 2
        }
        return baos.toByteArray()
    }

    /** 直接把 content uri 的内容流式写进 multipart，避免大文件进内存 */
    private class ContentUriRequestBody(
        private val resolver: android.content.ContentResolver,
        private val uri: Uri,
        private val mime: String
    ) : RequestBody() {

        private var resolvedLength = -1L
        private var lengthResolved = false

        override fun contentType() = mime.toMediaTypeOrNull()

        /**
         * Content-Length 必须和实际写入的字节数一致，否则服务器会认为协议错误直接重置连接。
         * 所以不用 provider 报的 SIZE，而是先取文件描述符长度（免读内容），取不到再实测一遍。
         */
        override fun contentLength(): Long {
            if (!lengthResolved) {
                lengthResolved = true
                resolvedLength = try {
                    val afd = resolver.openAssetFileDescriptor(uri, "r")
                    val len = afd?.length ?: -1L
                    try { afd?.close() } catch (_: Exception) {}
                    if (len >= 0) len else countBytes()
                } catch (e: Exception) {
                    -1L
                }
            }
            return resolvedLength
        }

        private fun countBytes(): Long {
            return try {
                resolver.openInputStream(uri)?.use { input ->
                    var total = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                    }
                    total
                } ?: -1L
            } catch (e: Exception) {
                -1L
            }
        }

        override fun writeTo(sink: BufferedSink) {
            val input = resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
            input.use { sink.writeAll(it.source()) }
        }
    }

    companion object {
        private const val TAG = "WebShareApp"
        private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
