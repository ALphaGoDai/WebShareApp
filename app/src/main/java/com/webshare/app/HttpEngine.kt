package com.webshare.app

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 流式 HTTP 客户端：走 App 自己的 DNS(DoH) + TLS 通道，边收边吐，适合大文件下载。
 *
 * 优先 OkHttp；部分服务器响应不符合 OkHttp 的严格解析（例如状态行没有 reason phrase），
 * 这时退回自解析的原始 Socket 通道（宽松解析，同样流式返回 body）。
 */
class HttpEngine(
    private val dnsResolver: DohDnsResolver?,
    /** 配置的站点主机名：https 取不到时允许回落明文再试一次（老站点可能只开 http） */
    private val configuredHost: String = ""
) {

    class StreamedResponse(
        val status: Int,
        val headers: Map<String, List<String>>,
        val contentLength: Long,
        val input: InputStream,
        private val onClose: () -> Unit
    ) : Closeable {
        override fun close() {
            try { input.close() } catch (_: Exception) {}
            try { onClose() } catch (_: Exception) {}
        }

        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { if (dnsResolver != null) dns(dnsResolver) }
            .cookieJar(WebCookieJar())
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun open(urlStr: String, extraHeaders: Map<String, String> = emptyMap()): StreamedResponse {
        var lastError: Exception? = null

        try {
            return openWithOkHttp(urlStr, extraHeaders)
        } catch (e: Exception) {
            lastError = e
        }

        // 宽松解析通道（原始 Socket）
        try {
            return openWithRawSocket(urlStr, extraHeaders)
        } catch (e: Exception) {
            lastError = e
        }

        // 配置站点：https 拿不到就用 http 再试（或反过来）
        val host = try { URL(urlStr).host } catch (e: Exception) { "" }
        if (configuredHost.isNotEmpty() && host == configuredHost) {
            val swapped = when {
                urlStr.startsWith("https://", true) ->
                    urlStr.replaceFirst("https://", "http://", true)
                urlStr.startsWith("http://", true) ->
                    urlStr.replaceFirst("http://", "https://", true)
                else -> null
            }
            if (swapped != null) {
                try {
                    return openWithRawSocket(swapped, extraHeaders)
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }

        throw (lastError ?: IOException("请求失败"))
    }

    private fun openWithOkHttp(
        urlStr: String,
        extraHeaders: Map<String, String>
    ): StreamedResponse {
        val builder = Request.Builder().url(urlStr)
        for ((k, v) in extraHeaders) {
            if (k.equals("Accept-Encoding", true) || k.equals("Cookie", true)) continue
            builder.header(k, v)
        }
        if (extraHeaders.keys.none { it.equals("User-Agent", true) }) {
            builder.header("User-Agent", DEFAULT_UA)
        }

        val resp = client.newCall(builder.build()).execute()
        val body = resp.body ?: run {
            resp.close()
            throw IOException("服务器未返回内容")
        }
        return StreamedResponse(
            status = resp.code,
            headers = resp.headers.toMultimap(),
            contentLength = body.contentLength(),
            input = body.byteStream(),
            onClose = { resp.close() }
        )
    }

    private fun openWithRawSocket(
        urlStr: String,
        extraHeaders: Map<String, String>
    ): StreamedResponse {
        val parsed = URL(urlStr)
        val host = parsed.host
        val port = parsed.port.takeIf { it > 0 } ?: parsed.defaultPort
        val isTls = parsed.protocol.equals("https", true)
        val ip = resolveHost(host)

        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(ip, port), 15000)
            raw.soTimeout = 30000
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }

        val socket: Socket = if (isTls) {
            try {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(host))
                ssl.sslParameters = params
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                    throw IOException("TLS证书主机名不匹配: $host")
                }
                ssl
            } catch (e: Exception) {
                try { raw.close() } catch (_: Exception) {}
                throw e
            }
        } else raw

        try {
            val path = (parsed.path.ifEmpty { "/" }) + (parsed.query?.let { "?$it" } ?: "")
            val hostHeader = if (port != 80 && port != 443) "$host:$port" else host
            val sb = StringBuilder()
            sb.append("GET ").append(path).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(hostHeader).append("\r\n")

            var hasUA = false
            for ((k, v) in extraHeaders) {
                if (k.equals("Accept-Encoding", true)) continue
                if (k.equals("Cookie", true)) continue
                if (k.equals("Host", true)) continue
                if (k.equals("Connection", true)) continue
                sb.append(k).append(": ").append(v).append("\r\n")
                if (k.equals("User-Agent", true)) hasUA = true
            }
            if (!hasUA) sb.append("User-Agent: ").append(DEFAULT_UA).append("\r\n")

            val cookies = android.webkit.CookieManager.getInstance().getCookie(urlStr)
            if (!cookies.isNullOrEmpty()) sb.append("Cookie: ").append(cookies).append("\r\n")

            sb.append("Accept-Encoding: identity\r\n")
            sb.append("Connection: close\r\n\r\n")
            socket.getOutputStream().write(sb.toString().toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()

            val input = BufferedInputStream(socket.getInputStream(), 16384)
            val head = readHead(input)

            return buildRawResponse(parsed, host, head, input, socket)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw e
        }
    }

    /** 逐字节读状态行 + 响应头，读到空行为止 */
    private fun readHead(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (sb.isEmpty()) throw IOException("服务器未返回任何数据")
                break
            }
            sb.append(b.toChar())
            val n = sb.length
            if (n >= 4 && sb[n - 4] == '\r' && sb[n - 3] == '\n' &&
                sb[n - 2] == '\r' && sb[n - 1] == '\n'
            ) break
            if (n >= 2 && sb[n - 2] == '\n' && sb[n - 1] == '\n') break
            if (n > 65536) throw IOException("响应头异常")
        }
        return sb.toString()
    }

    private fun buildRawResponse(
        parsed: URL,
        host: String,
        head: String,
        input: InputStream,
        socket: Socket
    ): StreamedResponse {
        val lines = head.split("\n").map { it.trimEnd('\r') }
        val statusLine = lines.firstOrNull { it.isNotBlank() } ?: throw IOException("空响应")
        val status = statusLine.trim().split("\\s+".toRegex()).getOrNull(1)?.toIntOrNull() ?: 200

        val headers = LinkedHashMap<String, MutableList<String>>()
        var contentLength = -1L
        var chunked = false
        var contentEncoding = ""

        for (i in lines.indices) {
            val line = lines[i]
            if (line.isBlank() || line == statusLine) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            headers.getOrPut(k) { mutableListOf() }.add(v)
            when {
                k.equals("Content-Length", true) -> contentLength = v.toLongOrNull() ?: -1L
                k.equals("Transfer-Encoding", true) && v.contains("chunked", true) -> chunked = true
                k.equals("Content-Encoding", true) -> contentEncoding = v.lowercase()
                k.equals("Set-Cookie", true) -> {
                    try {
                        android.webkit.CookieManager.getInstance()
                            .setCookie(parsed.toString(), v)
                    } catch (_: Exception) {}
                }
            }
        }

        var body: InputStream = input
        if (chunked) body = ChunkedInputStream(input)
        else if (contentLength >= 0) body = BoundedInputStream(input, contentLength)
        if (contentEncoding.contains("gzip")) body = java.util.zip.GZIPInputStream(body)

        return StreamedResponse(
            status = status,
            headers = headers,
            contentLength = if (chunked) -1L else contentLength,
            input = body,
            onClose = { try { socket.close() } catch (_: Exception) {} }
        )
    }

    private fun resolveHost(host: String): String {
        val r = dnsResolver ?: return host
        return try {
            r.lookup(host).firstOrNull()?.hostAddress ?: host
        } catch (e: Exception) {
            host
        }
    }

    private class BoundedInputStream(
        private val src: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = src.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() { src.close() }
    }

    private class ChunkedInputStream(private val src: InputStream) : InputStream() {
        private var chunkLeft = 0
        private var done = false

        private fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val b = src.read()
                if (b < 0) break
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }

        private fun nextChunk(): Boolean {
            if (done) return false
            if (chunkLeft > 0) return true
            val sizeStr = readLine().trim().split(';')[0].trim()
            val size = sizeStr.toIntOrNull(16) ?: 0
            if (size <= 0) {
                done = true
                return false
            }
            chunkLeft = size
            return true
        }

        override fun read(): Int {
            if (!nextChunk()) return -1
            val b = src.read()
            if (b < 0) { done = true; return -1 }
            chunkLeft--
            if (chunkLeft == 0) readLine()   // 丢掉 chunk 后的 CRLF
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!nextChunk()) return -1
            val n = src.read(b, off, minOf(len, chunkLeft))
            if (n < 0) { done = true; return -1 }
            chunkLeft -= n
            if (chunkLeft == 0) readLine()
            return n
        }

        override fun close() { src.close() }
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
