package com.webshare.app

import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

open class DohWebViewClient(
    private val dohEnabled: Boolean,
    private val dohUrl: String,
    private val forceHttpHost: String = ""
) : WebViewClient() {

    private val dnsResolver: DohDnsResolver? = if (dohEnabled && dohUrl.isNotEmpty()) {
        try { DohDnsResolver(dohUrl) } catch (e: Exception) { null }
    } else null

    private val httpsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply {
                if (dnsResolver != null) dns(dnsResolver)
            }
            .cookieJar(WebCookieJar())
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url ?: return null
        val scheme = url.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (request.method != "GET") return null

        // Force HTTP: WebView auto-upgrades http->https, undo that
        var urlStr = url.toString()
        val host = url.host ?: ""
        if (scheme == "https" && forceHttpHost.isNotEmpty() && host == forceHttpHost) {
            urlStr = urlStr.replace("https://", "http://", ignoreCase = true)
        }

        return try {
            if (urlStr.startsWith("https://")) {
                fetchWithOkHttp(urlStr, request.requestHeaders)
            } else {
                fetchWithRawSocket(urlStr, request.requestHeaders)
            }
        } catch (e: Exception) {
            makeErrorResponse(
                "连接失败: ${e.message ?: "未知错误"}\n\n" +
                    "请求地址: $urlStr\n" +
                    "DNS模式: $dohUrl"
            )
        }
    }

    private fun fetchWithOkHttp(
        urlStr: String,
        headers: Map<String, String>
    ): WebResourceResponse {
        val builder = okhttp3.Request.Builder().url(urlStr)
        for ((key, value) in headers) {
            if (!key.equals("Accept-Encoding", true) && !key.equals("Cookie", true)) {
                builder.header(key, value)
            }
        }
        if (headers.keys.none { it.equals("User-Agent", true) }) {
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
        }

        val response = httpsClient.newCall(builder.build()).execute()
        val ct = response.header("Content-Type") ?: "text/html"
        val parts = ct.split(";")
        val mime = parts[0].trim()
        val cs = if (parts.size > 1) parts[1].trim().removePrefix("charset=").trim() else "utf-8"

        val respHeaders = mutableMapOf<String, String>()
        for ((k, v) in response.headers) {
            if (!k.equals("Set-Cookie", true)) respHeaders[k] = v
        }

        val body = response.body?.byteStream()
            ?: throw java.io.IOException("Empty response body")

        return WebResourceResponse(mime, cs, response.code, response.message, respHeaders, body)
    }

    private fun fetchWithRawSocket(
        urlStr: String,
        headers: Map<String, String>
    ): WebResourceResponse {
        val parsed = URL(urlStr)
        val host = parsed.host
        val port = parsed.port.takeIf { it > 0 } ?: parsed.defaultPort
        val path = (parsed.path.ifEmpty { "/" }) +
            (parsed.query?.let { "?$it" } ?: "")

        // Resolve DNS via DoH or system
        val ip = resolveHost(host)

        val socket = Socket()
        socket.connect(InetSocketAddress(ip, port), 15000)
        socket.soTimeout = 15000

        try {
            val out = socket.getOutputStream()

            // Build HTTP request
            val sb = StringBuilder()
            sb.append("GET ").append(path).append(" HTTP/1.1\r\n")
            val hostHeader = if (port != 80) "$host:$port" else host
            sb.append("Host: ").append(hostHeader).append("\r\n")

            var hasUA = false
            for ((key, value) in headers) {
                if (key.equals("Accept-Encoding", true)) continue
                if (key.equals("Cookie", true)) continue
                if (key.equals("Host", true)) continue
                sb.append(key).append(": ").append(value).append("\r\n")
                if (key.equals("User-Agent", true)) hasUA = true
            }
            if (!hasUA) {
                sb.append("User-Agent: Mozilla/5.0 (Linux; Android 12) ")
                    .append("AppleWebKit/537.36 (KHTML, like Gecko) ")
                    .append("Chrome/120.0.0.0 Mobile Safari/537.36\r\n")
            }

            // Cookies
            val cookies = CookieManager.getInstance().getCookie(urlStr)
            if (!cookies.isNullOrEmpty()) {
                sb.append("Cookie: ").append(cookies).append("\r\n")
            }

            sb.append("Connection: close\r\n")
            sb.append("\r\n")

            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            out.flush()

            // Read full response
            val input = socket.getInputStream()
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                baos.write(buf, 0, read)
            }

            val responseData = baos.toByteArray()

            // Parse response (lenient — reason phrase may be empty)
            return parseRawResponse(responseData, urlStr)

        } finally {
            socket.close()
        }
    }

    private fun resolveHost(host: String): String {
        if (!dohEnabled || dnsResolver == null) return host
        return try {
            val addresses = dnsResolver!!.lookup(host)
            addresses.firstOrNull()?.hostAddress ?: host
        } catch (e: Exception) {
            host
        }
    }

    private fun parseRawResponse(data: ByteArray, originalUrl: String): WebResourceResponse {
        // Find header/body boundary
        val boundary = findHeaderEnd(data)
        if (boundary < 0) {
            throw java.io.IOException("Invalid HTTP response (no header end)")
        }

        val headerStr = String(data, 0, boundary, Charsets.ISO_8859_1)
        val bodyBytes = data.copyOfRange(boundary + 4, data.size)

        val lines = headerStr.split("\r\n")
        if (lines.isEmpty()) {
            throw java.io.IOException("Empty HTTP response")
        }

        // Parse status line (lenient)
        val statusLine = lines[0]
        val statusCode = parseStatusCode(statusLine)
        val reasonPhrase = parseReasonPhrase(statusLine)

        // Parse headers
        val responseHeaders = mutableMapOf<String, String>()
        var contentType = "text/html"
        var charset = "utf-8"
        var isChunked = false

        for (i in 1 until lines.size) {
            val line = lines[i]
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val key = line.substring(0, colonIdx).trim()
            val value = line.substring(colonIdx + 1).trim()

            when {
                key.equals("Content-Type", true) -> {
                    val parts = value.split(";")
                    contentType = parts[0].trim()
                    if (parts.size > 1) {
                        val csPart = parts[1].trim()
                        if (csPart.startsWith("charset=", true)) {
                            charset = csPart.substring(8).trim()
                        }
                    }
                }
                key.equals("Transfer-Encoding", true) -> {
                    if (value.contains("chunked", true)) isChunked = true
                }
                key.equals("Set-Cookie", true) -> {
                    CookieManager.getInstance().setCookie(originalUrl, value)
                }
                !key.equals("Content-Length", true) -> {
                    responseHeaders[key] = value
                }
            }
        }

        // Handle chunked encoding
        val finalBody = if (isChunked) dechunk(bodyBytes) else bodyBytes

        return WebResourceResponse(
            contentType,
            charset,
            statusCode,
            reasonPhrase,
            responseHeaders,
            ByteArrayInputStream(finalBody)
        )
    }

    private fun findHeaderEnd(data: ByteArray): Int {
        for (i in 0..data.size - 4) {
            if (data[i] == 0x0d.toByte() && data[i + 1] == 0x0a.toByte() &&
                data[i + 2] == 0x0d.toByte() && data[i + 3] == 0x0a.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun parseStatusCode(statusLine: String): Int {
        val parts = statusLine.trim().split("\\s+".toRegex())
        return if (parts.size >= 2) {
            parts[1].toIntOrNull() ?: 200
        } else 200
    }

    private fun parseReasonPhrase(statusLine: String): String {
        val parts = statusLine.trim().split("\\s+".toRegex(), limit = 3)
        return if (parts.size >= 3) parts[2] else "OK"
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        var pos = 0
        while (pos < data.size) {
            // Find chunk size line end
            var lineEnd = pos
            while (lineEnd < data.size - 1) {
                if (data[lineEnd] == 0x0d.toByte() && data[lineEnd + 1] == 0x0a.toByte()) break
                lineEnd++
            }
            if (lineEnd >= data.size - 1) break

            val sizeStr = String(data, pos, lineEnd - pos, Charsets.US_ASCII).trim()
            val chunkSize = sizeStr.split(";")[0].trim().toIntOrNull(16) ?: break
            if (chunkSize == 0) break

            val chunkStart = lineEnd + 2
            val chunkEnd = chunkStart + chunkSize
            if (chunkEnd > data.size) break

            baos.write(data, chunkStart, chunkSize)
            pos = chunkEnd + 2 // skip \r\n after chunk
        }
        return baos.toByteArray()
    }

    private fun makeErrorResponse(message: String): WebResourceResponse {
        val html = """
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { font-family: sans-serif; padding: 40px 20px; color: #333; text-align: center; }
                    h2 { color: #d32f2f; margin-bottom: 20px; }
                    pre { text-align: left; background: #f5f5f5; padding: 16px; border-radius: 8px; font-size: 13px;
                          overflow-x: auto; white-space: pre-wrap; word-wrap: break-word; }
                    .hint { margin-top: 20px; color: #666; font-size: 13px; }
                </style>
            </head>
            <body>
                <h2>连接失败</h2>
                <pre>$message</pre>
                <p class="hint">请到设置中尝试更换 DNS 服务器<br>
                推荐: DNSPod (DoH) 或 119.29.29.29 (UDP)</p>
            </body>
            </html>
        """.trimIndent()
        return WebResourceResponse(
            "text/html", "utf-8", 502, "Bad Gateway",
            emptyMap(), ByteArrayInputStream(html.toByteArray())
        )
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url ?: return false
        val s = url.scheme ?: return false
        if (s == "http" || s == "https") return false
        try {
            val intent = Intent(Intent.ACTION_VIEW, url)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            view?.context?.startActivity(intent)
        } catch (e: Exception) {
        }
        return true
    }
}

class WebCookieJar : CookieJar {
    private val cookieManager = CookieManager.getInstance()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            cookieManager.setCookie(url.toString(), cookie.toString())
        }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookies = cookieManager.getCookie(url.toString()) ?: return emptyList()
        return cookies.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
