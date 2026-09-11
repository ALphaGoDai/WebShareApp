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
import okhttp3.Request
import java.util.concurrent.TimeUnit

open class DohWebViewClient(
    private val dohEnabled: Boolean,
    private val dohUrl: String
) : WebViewClient() {

    private var dohClient: OkHttpClient? = null

    init {
        if (dohEnabled && dohUrl.isNotEmpty()) {
            val resolver = DohDnsResolver(dohUrl)
            dohClient = OkHttpClient.Builder()
                .dns(resolver)
                .cookieJar(WebCookieJar())
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (!dohEnabled) return null
        val client = dohClient ?: return null
        val url = request?.url ?: return null

        val scheme = url.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null

        // 只拦截 GET 请求（WebResourceRequest 无法获取 POST body）
        if (request.method != "GET") return null

        return try {
            val builder = Request.Builder().url(url.toString())

            for ((key, value) in request.requestHeaders) {
                // 不复制 Accept-Encoding，让 OkHttp 处理压缩
                // 不复制 Cookie，通过 CookieJar 统一管理
                if (!key.equals("Accept-Encoding", ignoreCase = true) &&
                    !key.equals("Cookie", ignoreCase = true)
                ) {
                    builder.header(key, value)
                }
            }

            val response = client.newCall(builder.build()).execute()

            // 解析 Content-Type 和 charset
            val contentTypeHeader = response.header("Content-Type") ?: "text/html"
            val parts = contentTypeHeader.split(";")
            val mimeType = parts[0].trim()
            val charset = if (parts.size > 1) {
                parts[1].trim().removePrefix("charset=").trim()
            } else {
                "utf-8"
            }

            // 构建响应头（排除 Set-Cookie，由 CookieJar 处理）
            val responseHeaders = mutableMapOf<String, String>()
            for ((key, value) in response.headers) {
                if (!key.equals("Set-Cookie", ignoreCase = true)) {
                    responseHeaders[key] = value
                }
            }

            val body = response.body?.byteStream() ?: return null

            WebResourceResponse(
                mimeType,
                charset,
                response.code,
                response.message,
                responseHeaders,
                body
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url ?: return false
        val scheme = url.scheme ?: return false

        if (scheme == "http" || scheme == "https") {
            // 在 WebView 内打开
            return false
        }

        // 非 http(s) 协议交给系统处理
        try {
            val intent = Intent(Intent.ACTION_VIEW, url)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            view?.context?.startActivity(intent)
        } catch (e: Exception) {
            // 没有可处理的应用
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
