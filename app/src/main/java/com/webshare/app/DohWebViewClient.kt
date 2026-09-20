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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

open class DohWebViewClient(
    private val dohEnabled: Boolean,
    private val dohUrl: String,
    private val forceHttpHost: String = ""
) : WebViewClient() {

    private var dohClient: OkHttpClient? = null
    private var plainClient: OkHttpClient? = null

    init {
        if (dohEnabled && dohUrl.isNotEmpty()) {
            try {
                val resolver = DohDnsResolver(dohUrl)
                dohClient = OkHttpClient.Builder()
                    .dns(resolver)
                    .cookieJar(WebCookieJar())
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
            } catch (e: Exception) {
                dohClient = null
            }
        }

        plainClient = OkHttpClient.Builder()
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

        val client = if (dohEnabled) dohClient else plainClient
        if (client == null) return null

        var urlStr = url.toString()

        // CRITICAL: Force HTTP when WebView auto-upgrades to HTTPS
        // for the host that the user configured as HTTP
        if (scheme == "https" && forceHttpHost.isNotEmpty()) {
            val httpUrl = urlStr.toHttpUrlOrNull()
            if (httpUrl != null && httpUrl.host == forceHttpHost) {
                urlStr = urlStr.replace("https://", "http://")
            }
        }

        return try {
            val builder = Request.Builder().url(urlStr)

            for ((key, value) in request.requestHeaders) {
                if (!key.equals("Accept-Encoding", ignoreCase = true) &&
                    !key.equals("Cookie", ignoreCase = true)
                ) {
                    builder.header(key, value)
                }
            }

            val hasUA = request.requestHeaders.keys.any {
                it.equals("User-Agent", ignoreCase = true)
            }
            if (!hasUA) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
                )
            }

            val response = client.newCall(builder.build()).execute()

            val contentTypeHeader = response.header("Content-Type") ?: "text/html"
            val parts = contentTypeHeader.split(";")
            val mimeType = parts[0].trim()
            val charset = if (parts.size > 1) {
                parts[1].trim().removePrefix("charset=").trim()
            } else {
                "utf-8"
            }

            val responseHeaders = mutableMapOf<String, String>()
            for ((key, value) in response.headers) {
                if (!key.equals("Set-Cookie", ignoreCase = true)) {
                    responseHeaders[key] = value
                }
            }

            val body = response.body?.byteStream()
            if (body == null) {
                return makeErrorResponse("服务器返回空响应")
            }

            WebResourceResponse(
                mimeType,
                charset,
                response.code,
                response.message,
                responseHeaders,
                body
            )
        } catch (e: Exception) {
            // CRITICAL: Never return null - that lets WebView retry with HTTPS
            makeErrorResponse(
                "连接失败: ${e.message ?: "未知错误"}\n\n" +
                    "请求地址: $urlStr\n" +
                    "DNS模式: ${if (dohEnabled) dohUrl else "系统DNS"}\n\n" +
                    "可能原因:\n" +
                    "1. DNS服务器无法解析此域名\n" +
                    "2. 服务器不可达\n" +
                    "3. 网络连接问题"
            )
        }
    }

    private fun makeErrorResponse(message: String): WebResourceResponse {
        val html = """
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body {
                        font-family: sans-serif;
                        padding: 40px 20px;
                        color: #333;
                        text-align: center;
                    }
                    h2 { color: #d32f2f; margin-bottom: 20px; }
                    pre {
                        text-align: left;
                        background: #f5f5f5;
                        padding: 16px;
                        border-radius: 8px;
                        font-size: 13px;
                        overflow-x: auto;
                        white-space: pre-wrap;
                        word-wrap: break-word;
                    }
                    .hint {
                        margin-top: 20px;
                        color: #666;
                        font-size: 13px;
                    }
                </style>
            </head>
            <body>
                <h2>连接失败</h2>
                <pre>$message</pre>
                <p class="hint">请到设置中尝试更换 DNS 服务器<br>
                推荐: DNSPod (DoH) 或 119.29.29.29 (UDP)<br>
                或: 117.50.11.11 (华为DNS, UDP模式)</p>
            </body>
            </html>
        """.trimIndent()
        return WebResourceResponse(
            "text/html",
            "utf-8",
            502,
            "Bad Gateway",
            emptyMap(),
            ByteArrayInputStream(html.toByteArray())
        )
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url ?: return false
        val s = url.scheme ?: return false

        if (s == "http" || s == "https") {
            return false
        }

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
