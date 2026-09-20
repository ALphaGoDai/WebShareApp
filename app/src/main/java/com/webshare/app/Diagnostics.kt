package com.webshare.app

import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Diagnostics {

    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun run(targetUrl: String, dohEnabled: Boolean, dohUrl: String, appVersion: String = ""): String {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        sb.append("诊断时间: ").append(ts).append("\n")
        if (appVersion.isNotEmpty()) sb.append("App版本: v").append(appVersion).append("\n")
        sb.append("目标: ").append(targetUrl).append("\n\n")

        val parsed = try {
            URL(targetUrl)
        } catch (e: Exception) {
            return sb.toString() + "URL 无效: ${e.message}"
        }
        val host = parsed.host
        if (host.isNullOrEmpty()) return sb.toString() + "URL 缺少主机名"
        val port = parsed.port.takeIf { it > 0 } ?: parsed.defaultPort
        val isHttps = parsed.protocol.equals("https", ignoreCase = true)
        sb.append("主机: ").append(host).append("   端口: ").append(port)
            .append("   协议: ").append(if (isHttps) "HTTPS" else "HTTP").append("\n\n")

        var resolver: DohDnsResolver? = null
        var customIps: List<InetAddress> = emptyList()

        if (dohEnabled && dohUrl.isNotEmpty()) {
            sb.append("[1] 自定义DNS (").append(dohUrl).append(")\n")
            try {
                val t0 = System.currentTimeMillis()
                resolver = DohDnsResolver(dohUrl)
                customIps = resolver.lookup(host)
                sb.append("    → ")
                    .append(customIps.joinToString { it.hostAddress ?: "?" })
                    .append("  (耗时 ").append(System.currentTimeMillis() - t0)
                    .append("ms)\n\n")
            } catch (e: Exception) {
                sb.append("    → 失败: ").append(e.message).append("\n\n")
            }
        }

        sb.append("[2] 系统DNS\n")
        val sysIps: List<InetAddress> = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            emptyList()
        }
        sb.append(
            if (sysIps.isEmpty()) "    → 失败\n\n"
            else "    → ${sysIps.joinToString { it.hostAddress ?: "?" }}\n\n"
        )

        val testIp = (customIps.firstOrNull() ?: sysIps.firstOrNull())?.hostAddress ?: host

        sb.append("[3] TCP连接 ").append(testIp).append(":").append(port).append("\n")
        val t1 = System.currentTimeMillis()
        val tcpOk = try {
            Socket().use { s -> s.connect(InetSocketAddress(testIp, port), 8000) }
            sb.append("    → 成功  (耗时 ").append(System.currentTimeMillis() - t1)
                .append("ms)\n\n")
            true
        } catch (e: Exception) {
            sb.append("    → 失败: ").append(e.message).append("\n\n")
            false
        }

        var appOk = false
        if (isHttps) {
            sb.append("[4] HTTPS请求 (应用实际路径: DoH + TLS)\n")
            appOk = probeHttps(sb, targetUrl, resolver)
            sb.append("\n")
            if (!appOk) {
                sb.append("[5] 追加探测: 明文HTTP\n")
                probePlainHttp(sb, host, port, testIp, "/")
                sb.append("\n")
            }
        } else {
            sb.append("[4] 明文HTTP请求\n")
            appOk = probePlainHttp(sb, host, port, testIp, parsed.path ?: "/")
            sb.append("\n")
            if (!appOk) {
                sb.append("[5] 追加探测: TLS\n")
                val httpsUrl = targetUrl.replaceFirst("http://", "https://", true)
                val tlsOk = probeHttps(sb, httpsUrl, resolver)
                if (tlsOk) {
                    sb.append("    → 服务器其实只支持加密连接，建议网址改用 https://\n")
                }
                sb.append("\n")
            }
        }

        sb.append("结论:\n")
        if (sysIps.any { it.isLoopbackAddress }) {
            sb.append("· 运营商/系统DNS把该域名劫持到127.0.0.1，")
                .append("所以普通浏览器和普通App都打不开（你的Chrome必须配自定义DNS的原因）。")
                .append("本App的自定义DNS已绕过此问题，请保持开启。\n")
        }
        if (customIps.isNotEmpty()) sb.append("· 自定义DNS解析正常\n")
        if (!tcpOk) sb.append("· TCP不通：手机网络到服务器不可达，请检查服务器/防火墙\n")
        if (appOk) {
            sb.append("· 应用层请求成功，网站可达。如主界面仍打不开，")
                .append("请长按右下角刷新按钮清除缓存后重试\n")
        }

        return sb.toString()
    }

    private fun buildClient(resolver: DohDnsResolver?): OkHttpClient {
        val cb = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
        if (resolver != null) cb.dns(resolver)
        return cb.build()
    }

    private fun probeHttps(
        sb: StringBuilder,
        url: String,
        resolver: DohDnsResolver?
    ): Boolean {
        return try {
            val t = System.currentTimeMillis()
            val resp = buildClient(resolver).newCall(
                okhttp3.Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .build()
            ).execute()
            try {
                val bytes = resp.body?.bytes()
                sb.append("    → HTTP ").append(resp.code)
                    .append(" ").append(resp.message.ifEmpty { "(空reason, 已兼容)" })
                    .append("  内容 ").append(bytes?.size ?: 0).append(" 字节")
                    .append("  (耗时 ").append(System.currentTimeMillis() - t)
                    .append("ms)\n")
                sb.append("    → Content-Type: ")
                    .append(resp.header("Content-Type") ?: "-").append("\n")
                resp.header("Location")?.let {
                    sb.append("    → Location: ").append(it).append("\n")
                }
                true
            } finally {
                resp.close()
            }
        } catch (e: Exception) {
            sb.append("    → 失败: ").append(e.message).append("\n")
            false
        }
    }

    private fun probePlainHttp(
        sb: StringBuilder,
        host: String,
        port: Int,
        ip: String,
        path: String
    ): Boolean {
        return try {
            val t = System.currentTimeMillis()
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), 8000)
                s.soTimeout = 8000
                val hostHeader = if (port != 80) "$host:$port" else host
                val req = "GET ${path.ifEmpty { "/" }} HTTP/1.1\r\n" +
                    "Host: $hostHeader\r\n" +
                    "User-Agent: $UA\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n\r\n"
                s.getOutputStream().apply {
                    write(req.toByteArray())
                    flush()
                }
                val input = s.getInputStream()
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    baos.write(buf, 0, n)
                }
                val data = baos.toByteArray()
                if (data.isEmpty()) {
                    sb.append("    → 服务器未返回任何数据\n")
                    false
                } else {
                    val firstLine = String(
                        data, 0, minOf(data.size, 120), Charsets.ISO_8859_1
                    ).lineSequence().firstOrNull() ?: ""
                    sb.append("    → ").append(firstLine)
                        .append("  (").append(data.size).append("字节, 耗时 ")
                        .append(System.currentTimeMillis() - t).append("ms)\n")
                    true
                }
            }
        } catch (e: Exception) {
            sb.append("    → 失败: ").append(e.message)
                .append(" (若为 Connection reset: 服务器只接受加密连接)\n")
            false
        }
    }
}
