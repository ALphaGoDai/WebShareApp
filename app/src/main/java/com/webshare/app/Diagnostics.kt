package com.webshare.app

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diagnostics {

    fun run(targetUrl: String, dohEnabled: Boolean, dohUrl: String): String {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        sb.append("诊断时间: ").append(ts).append("\n")
        sb.append("目标: ").append(targetUrl).append("\n\n")

        val parsed = try {
            URL(targetUrl)
        } catch (e: Exception) {
            return sb.toString() + "URL 无效: ${e.message}"
        }
        val host = parsed.host
        if (host.isNullOrEmpty()) return sb.toString() + "URL 缺少主机名"
        val port = parsed.port.takeIf { it > 0 } ?: parsed.defaultPort
        sb.append("主机: ").append(host).append("   端口: ").append(port).append("\n\n")

        var customIps: List<InetAddress> = emptyList()

        if (dohEnabled && dohUrl.isNotEmpty()) {
            sb.append("[1] 自定义DNS (").append(dohUrl).append(")\n")
            try {
                val t0 = System.currentTimeMillis()
                val resolver = DohDnsResolver(dohUrl)
                customIps = resolver.lookup(host)
                val dt = System.currentTimeMillis() - t0
                sb.append("    → ")
                    .append(customIps.joinToString { it.hostAddress ?: "?" })
                    .append("  (耗时 ").append(dt).append("ms)\n\n")
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
        try {
            Socket().use { s -> s.connect(InetSocketAddress(testIp, port), 8000) }
            sb.append("    → 成功  (耗时 ").append(System.currentTimeMillis() - t1)
                .append("ms)\n\n")
        } catch (e: Exception) {
            sb.append("    → 失败: ").append(e.message).append("\n\n")
        }

        sb.append("[4] HTTP请求 GET ").append(parsed.path ?: "/").append("\n")
        try {
            val t2 = System.currentTimeMillis()
            Socket().use { s ->
                s.connect(InetSocketAddress(testIp, port), 8000)
                s.soTimeout = 8000
                val hostHeader = if (port != 80) "$host:$port" else host
                val req = "GET ${parsed.path ?: "/"} HTTP/1.1\r\n" +
                    "Host: $hostHeader\r\n" +
                    "User-Agent: Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n\r\n"
                s.getOutputStream().apply {
                    write(req.toByteArray(Charsets.UTF_8))
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
                sb.append("    → 收到 ").append(data.size).append(" 字节  (耗时 ")
                    .append(System.currentTimeMillis() - t2).append("ms)\n")
                val preview = String(data, 0, minOf(data.size, 300), Charsets.ISO_8859_1)
                val firstLines = preview.lineSequence().take(5).joinToString(" | ")
                sb.append("    → 响应开头: ").append(firstLines).append("\n\n")
            }
        } catch (e: Exception) {
            sb.append("    → 失败: ").append(e.message).append("\n\n")
        }

        sb.append("提示:\n")
        sb.append("· [1]失败 → DoH/UDP 服务器不可用，换一个 DNS\n")
        sb.append("· [1]能解析但[3]失败 → 手机网络到服务器不通\n")
        sb.append("· [3]成功但[4]无响应 → 服务器接受连接但不返回数据\n")
        sb.append("· [4]有正常 HTTP 响应 → 网络没问题，问题在应用层\n")
        return sb.toString()
    }
}
