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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

open class DohWebViewClient(
    private val dohEnabled: Boolean,
    private val dohUrl: String,
    private val forceHttpHost: String = "",
    private val configuredHost: String = "",
    private val appVersion: String = "",
    private val shimJs: String = ""
) : WebViewClient() {

    private val dnsResolver: DohDnsResolver? = if (dohEnabled && dohUrl.isNotEmpty()) {
        try { DohDnsResolver(dohUrl) } catch (e: Exception) { null }
    } else null

    // Hosts that rejected cleartext but work over TLS
    private val tlsOnlyHosts = ConcurrentHashMap<String, Boolean>()

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

        var urlStr = url.toString()
        val host = url.host ?: ""

        // WebView (especially Huawei/HarmonyOS) auto-upgrades http to https.
        // Undo the upgrade when the configured site is http://.
        if (scheme == "https" && forceHttpHost.isNotEmpty() && host == forceHttpHost) {
            urlStr = urlStr.replace("https://", "http://", ignoreCase = true)
        }

        return try {
            smartFetch(urlStr, host, request.requestHeaders, request.isForMainFrame)
        } catch (e: Exception) {
            makeErrorResponse(buildErrorText(urlStr, host, e))
        }
    }

    private fun toHttps(u: String) =
        u.replaceFirst("http://", "https://", ignoreCase = true)

    private fun smartFetch(
        urlStr: String,
        host: String,
        headers: Map<String, String>,
        isMainFrame: Boolean
    ): WebResourceResponse {
        if (urlStr.startsWith("https://", ignoreCase = true)) {
            try {
                return maybeInjectShim(fetchWithOkHttp(urlStr, headers), isMainFrame)
            } catch (e: Exception) {
                // OkHttp is strict about response parsing; retry over a raw
                // TLS socket with the lenient parser.
                try {
                    return maybeInjectShim(fetchWithTlsRawSocket(urlStr, headers), isMainFrame)
                } catch (e2: Exception) {
                    // Site may actually be plain http (misconfigured scheme)
                    if (configuredHost.isNotEmpty() && host == configuredHost) {
                        return maybeInjectShim(
                            fetchWithRawSocket(
                                urlStr.replaceFirst("https://", "http://", ignoreCase = true),
                                headers
                            ),
                            isMainFrame
                        )
                    }
                    throw e2
                }
            }
        }

        // Cleartext URL
        if (tlsOnlyHosts[host] == true) {
            return maybeInjectShim(fetchWithTlsRawSocket(toHttps(urlStr), headers), isMainFrame)
        }
        return try {
            maybeInjectShim(fetchWithRawSocket(urlStr, headers), isMainFrame)
        } catch (e: Exception) {
            // Some servers only accept TLS even on non-standard ports
            val resp = maybeInjectShim(fetchWithTlsRawSocket(toHttps(urlStr), headers), isMainFrame)
            tlsOnlyHosts[host] = true
            resp
        }
    }

    /**
     * Embed the fetch/XHR bridge shim into the main HTML document, ahead of
     * any page script. evaluateJavascript in onPageStarted/Finished runs after
     * page scripts, which is too late to intercept their network calls.
     */
    private fun maybeInjectShim(
        resp: WebResourceResponse,
        isMainFrame: Boolean
    ): WebResourceResponse {
        if (!isMainFrame || shimJs.isEmpty()) return resp
        val mime = resp.mimeType ?: return resp
        if (!mime.contains("html", ignoreCase = true)) return resp
        return try {
            val charsetName = resp.encoding?.takeIf { it.isNotEmpty() } ?: "utf-8"
            val data = resp.data?.readBytes() ?: return resp
            val html = String(data, charset(charsetName))

            val tag = "<script>$shimJs</script>"
            var injected: String = tag + html
            val headIdx = html.indexOf("<head>", ignoreCase = true)
            if (headIdx >= 0) {
                val at = headIdx + "<head>".length
                injected = html.substring(0, at) + tag + html.substring(at)
            } else {
                val headTag = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
                if (headTag != null) {
                    val at = headTag.range.last + 1
                    injected = html.substring(0, at) + tag + html.substring(at)
                } else {
                    val htmlTag = Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)
                    if (htmlTag != null) {
                        val at = htmlTag.range.last + 1
                        injected = html.substring(0, at) + tag + html.substring(at)
                    }
                }
            }

            val headers = mutableMapOf<String, String>()
            resp.responseHeaders?.forEach { (k, v) ->
                if (!k.equals("Content-Length", true)) headers[k] = v
            }
            WebResourceResponse(
                resp.mimeType,
                charsetName,
                resp.statusCode,
                resp.reasonPhrase,
                headers,
                ByteArrayInputStream(injected.toByteArray(charset(charsetName)))
            )
        } catch (e: Exception) {
            resp
        }
    }

    private fun buildErrorText(urlStr: String, host: String, e: Exception): String {
        val sb = StringBuilder()
        sb.append("连接失败: ").append(e.message ?: "未知错误").append("\n\n")
        sb.append("请求地址: ").append(urlStr).append("\n")
        sb.append("主机: ").append(host).append("\n")
        sb.append(resolveInfo(host)).append("\n")
        sb.append("DNS模式: ").append(if (dohUrl.isNotEmpty()) dohUrl else "系统默认").append("\n")
        if (appVersion.isNotEmpty()) {
            sb.append("应用版本: ").append(appVersion).append("\n")
        }
        sb.append("设备网络: 请在设置中运行「网络诊断」获取详细报告")
        return sb.toString()
    }

    private fun resolveInfo(host: String): String {
        return try {
            if (dnsResolver != null) {
                val ips = dnsResolver.lookup(host)
                "DNS解析: ${ips.joinToString { it.hostAddress ?: "?" }}"
            } else {
                "DNS解析: 使用系统默认DNS"
            }
        } catch (e: Exception) {
            "DNS解析失败: ${e.message}"
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

        httpsClient.newCall(builder.build()).execute().use { response ->
            val ct = response.header("Content-Type") ?: "text/html"
            val parts = ct.split(";")
            val mime = parts[0].trim()
            val cs = if (parts.size > 1) parts[1].trim().removePrefix("charset=").trim() else "utf-8"

            val respHeaders = mutableMapOf<String, String>()
            for ((k, v) in response.headers) {
                if (!k.equals("Set-Cookie", true)) respHeaders[k] = v
            }

            val body = response.body?.bytes()
                ?: throw java.io.IOException("Empty response body")

            // Android's WebResourceResponse rejects empty reason phrases, but the
            // site's status line may omit it (e.g. "HTTP/1.1 200").
            val reason = response.message.ifEmpty { "OK" }
            return WebResourceResponse(
                mime, cs, response.code, reason, respHeaders,
                ByteArrayInputStream(body)
            )
        }
    }

    private fun fetchWithRawSocket(
        urlStr: String,
        headers: Map<String, String>
    ): WebResourceResponse {
        val parsed = URL(urlStr)
        val port = parsed.port.takeIf { it > 0 } ?: parsed.defaultPort
        val ip = resolveHost(parsed.host)

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(ip, port), 15000)
            socket.soTimeout = 15000
            return exchangeOverSocket(socket, parsed, urlStr, headers)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun fetchWithTlsRawSocket(
        urlStr: String,
        headers: Map<String, String>
    ): WebResourceResponse {
        val parsed = URL(urlStr)
        val host = parsed.host
        val port = parsed.port.takeIf { it > 0 } ?: parsed.defaultPort
        val ip = resolveHost(host)

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(ip, port), 15000)
            raw.soTimeout = 15000
            val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
            try {
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(host))
                ssl.sslParameters = params
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier()
                        .verify(host, ssl.session)
                ) {
                    throw java.io.IOException("TLS证书主机名不匹配: $host")
                }
                return exchangeOverSocket(ssl, parsed, urlStr, headers)
            } finally {
                try { ssl.close() } catch (_: Exception) {}
            }
        } finally {
            try { raw.close() } catch (_: Exception) {}
        }
    }

    private fun exchangeOverSocket(
        socket: Socket,
        parsed: URL,
        originalUrl: String,
        headers: Map<String, String>
    ): WebResourceResponse {
        val host = parsed.host
        val port = parsed.port.takeIf { it > 0 } ?: parsed.defaultPort
        val path = (parsed.path.ifEmpty { "/" }) +
            (parsed.query?.let { "?$it" } ?: "")

        val out = socket.getOutputStream()

        val sb = StringBuilder()
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n")
        val hostHeader = if (port != 80 && port != 443) "$host:$port" else host
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

        val cookies = CookieManager.getInstance().getCookie(originalUrl)
        if (!cookies.isNullOrEmpty()) {
            sb.append("Cookie: ").append(cookies).append("\r\n")
        }

        sb.append("Connection: close\r\n")
        sb.append("\r\n")

        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()

        val input = socket.getInputStream()
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            baos.write(buf, 0, read)
        }

        val responseData = baos.toByteArray()
        if (responseData.isEmpty()) {
            throw java.io.IOException("服务器接受连接但未返回任何数据")
        }

        return parseRawResponse(responseData, originalUrl)
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
        // Skip leading CR/LF junk some servers emit before the status line
        var start = 0
        while (start < data.size &&
            (data[start] == 0x0d.toByte() || data[start] == 0x0a.toByte())
        ) start++

        val boundary = findHeaderEnd(data, start)
        if (boundary < 0) {
            throw java.io.IOException("Invalid HTTP response (no header end)")
        }

        val headerEndLen = if (data[boundary] == 0x0d.toByte()) 4 else 2
        val headerStr = String(data, start, boundary - start, Charsets.ISO_8859_1)
        val bodyBytes = data.copyOfRange(
            minOf(boundary + headerEndLen, data.size), data.size
        )

        val lines = headerStr.split("\n").map { it.trimEnd('\r') }
        if (lines.isEmpty() || lines[0].isEmpty()) {
            throw java.io.IOException("Empty HTTP response")
        }

        val statusLine = lines[0]
        val statusCode = parseStatusCode(statusLine)
        val reasonPhrase = parseReasonPhrase(statusLine)

        val responseHeaders = mutableMapOf<String, String>()
        var contentType = "text/html"
        var charset = "utf-8"
        var isChunked = false
        var isGzip = false

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
                key.equals("Content-Encoding", true) -> {
                    if (value.contains("gzip", true)) isGzip = true
                }
                key.equals("Set-Cookie", true) -> {
                    CookieManager.getInstance().setCookie(originalUrl, value)
                }
                !key.equals("Content-Length", true) -> {
                    responseHeaders[key] = value
                }
            }
        }

        var finalBody = if (isChunked) dechunk(bodyBytes) else bodyBytes

        if (isGzip && finalBody.size > 0) {
            try {
                finalBody = GZIPInputStream(ByteArrayInputStream(finalBody)).readBytes()
                // Body was decoded; drop the header so WebView doesn't decode again
                responseHeaders.keys.removeAll { it.equals("Content-Encoding", true) }
            } catch (e: Exception) {
                // Leave as-is; WebView may handle it
            }
        }

        return WebResourceResponse(
            contentType,
            charset,
            statusCode,
            reasonPhrase,
            responseHeaders,
            ByteArrayInputStream(finalBody)
        )
    }

    private fun findHeaderEnd(data: ByteArray, from: Int): Int {
        var i = from
        while (i <= data.size - 4) {
            if (data[i] == 0x0d.toByte() && data[i + 1] == 0x0a.toByte() &&
                data[i + 2] == 0x0d.toByte() && data[i + 3] == 0x0a.toByte()
            ) {
                return i
            }
            i++
        }
        i = from
        while (i <= data.size - 2) {
            if (data[i] == 0x0a.toByte() && data[i + 1] == 0x0a.toByte()) return i
            i++
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
        return if (parts.size >= 3 && parts[2].isNotEmpty()) parts[2] else "OK"
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
            val chunkStart = afterLine
            val chunkEnd = chunkStart + chunkSize
            if (chunkEnd > data.size) break

            baos.write(data, chunkStart, chunkSize)
            pos = chunkEnd + 2
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
                    pre { text-align: left; background: #f5f5f5; padding: 16px; border-radius: 8px;
                          font-size: 12px; overflow-x: auto; white-space: pre-wrap; word-wrap: break-word; }
                    .hint { margin-top: 20px; color: #666; font-size: 13px; }
                </style>
            </head>
            <body>
                <h2>连接失败</h2>
                <pre>${htmlEscape(message)}</pre>
                <p class="hint">请到设置中尝试更换 DNS 服务器，或运行「网络诊断」</p>
            </body>
            </html>
        """.trimIndent()
        return WebResourceResponse(
            "text/html", "utf-8", 502, "Bad Gateway",
            emptyMap(), ByteArrayInputStream(html.toByteArray())
        )
    }

    private fun htmlEscape(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
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
