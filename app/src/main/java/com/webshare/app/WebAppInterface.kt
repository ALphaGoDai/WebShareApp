package com.webshare.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
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

    private val mainHandler = Handler(Looper.getMainLooper())

    private val executor: ExecutorService = Executors.newCachedThreadPool()

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
     * 结果通过回调返回（异步），回调收到一个对象:
     *   { ok: true/false, status: 200, data: "响应体文本", error: null }
     * callback 须为全局函数名，如 "onPostDone"。
     * 网页用法:
     *   Android.httpPost("https://site/api", "a=1&b=2",
     *                   "application/x-www-form-urlencoded", "onPostDone")
     *   function onPostDone(r){ if(r.ok) alert(r.status) }
     * callback 可传空字符串，则默认调用 window.onHttpPostResult。
     */
    @JavascriptInterface
    fun httpPost(url: String, body: String, contentType: String, callback: String) {
        enqueue(url, "POST", body, contentType, callback)
    }

    /** GET 版本，用法同 httpPost（body 传空字符串）。 */
    @JavascriptInterface
    fun httpGet(url: String, callback: String) {
        enqueue(url, "GET", "", "", callback)
    }

    private fun enqueue(
        url: String,
        method: String,
        body: String,
        contentType: String,
        callback: String
    ) {
        executor.execute {
            val result = try {
                executeRequest(url, method, body, contentType)
            } catch (e: Exception) {
                failResult(e.message ?: "未知错误")
            }
            deliver(callback.ifEmpty { "window.onHttpPostResult" }, result)
        }
    }

    private fun okResult(status: Int, data: String): String {
        return JSONObject().apply {
            put("ok", true)
            put("status", status)
            put("data", data)
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
        if (!dohEnabled || dohUrl.isEmpty()) return host
        return try {
            DohDnsResolver(dohUrl).lookup(host).firstOrNull()?.hostAddress ?: host
        } catch (e: Exception) {
            host
        }
    }

    private fun executeRequest(
        url: String,
        method: String,
        body: String,
        contentType: String
    ): String {
        return try {
            executeWithOkHttp(url, method, body, contentType)
        } catch (e: Exception) {
            // OkHttp strict parsing failed; retry over raw socket with the
            // lenient parser (plain first, then TLS).
            try {
                executeWithRawSocket(url, method, body, contentType, false)
            } catch (e2: Exception) {
                executeWithRawSocket(url, method, body, contentType, true)
            }
        }
    }

    private fun buildClient(): OkHttpClient {
        val cb = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
        if (dohEnabled && dohUrl.isNotEmpty()) {
            try { cb.dns(DohDnsResolver(dohUrl)) } catch (e: Exception) {}
        }
        return cb.build()
    }

    private fun executeWithOkHttp(
        url: String,
        method: String,
        body: String,
        contentType: String
    ): String {
        val builder = Request.Builder().url(url)
        if (method == "POST") {
            val ct = if (contentType.isEmpty()) "application/x-www-form-urlencoded" else contentType
            val reqBody = (body.ifEmpty { "" }).toRequestBody(ct.toMediaType())
            builder.post(reqBody)
        } else {
            builder.get()
        }
        builder.header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        buildClient().newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            return okResult(resp.code, text)
        }
    }

    private fun executeWithRawSocket(
        url: String,
        method: String,
        body: String,
        contentType: String,
        useTls: Boolean
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
                // For https URLs always wrap in TLS; useTls=true forces it even for http://
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
            return parseAndBuildResult(data)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun parseAndBuildResult(raw: ByteArray): String {
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
        for (j in 1 until lines.size) {
            val line = lines[j]
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim().lowercase()
            val v = line.substring(idx + 1).trim()
            if (k == "transfer-encoding" && v.contains("chunked", true)) isChunked = true
            if (k == "content-encoding" && v.contains("gzip", true)) isGzip = true
        }

        if (isChunked) bodyBytes = dechunk(bodyBytes)
        if (isGzip && bodyBytes.isNotEmpty()) {
            try {
                bodyBytes = java.util.zip.GZIPInputStream(ByteArrayInputStream(bodyBytes)).readBytes()
            } catch (e: Exception) {}
        }

        return okResult(status, String(bodyBytes, Charsets.UTF_8))
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
}
