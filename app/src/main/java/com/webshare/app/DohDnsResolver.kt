package com.webshare.app

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DohDnsResolver(private val dnsServer: String) : Dns {

    private val isUdpMode: Boolean = isIpAddress(dnsServer)
    private val dohHost: String = if (!isUdpMode) {
        try { java.net.URL(dnsServer).host } catch (e: Exception) { "" }
    } else ""

    private val dohClient: OkHttpClient by lazy {
        val bootstrapDns = BootstrapDns(dohHost)
        OkHttpClient.Builder()
            .dns(bootstrapDns)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private data class CacheEntry(val addresses: List<InetAddress>, val expiry: Long)

    override fun lookup(hostname: String): List<InetAddress> {
        val cached = cache[hostname]
        if (cached != null && System.currentTimeMillis() < cached.expiry) {
            return cached.addresses
        }

        // Try primary method (DoH or UDP)
        try {
            val ips = if (isUdpMode) {
                queryUdpDns(hostname, dnsServer)
            } else {
                queryDoh(hostname)
            }
            if (ips.isNotEmpty()) {
                val addresses = ips.map { InetAddress.getByName(it) }
                cache[hostname] = CacheEntry(addresses, System.currentTimeMillis() + CACHE_TTL)
                return addresses
            }
        } catch (e: Exception) {
            // Primary failed, try fallbacks below
        }

        // Fallback: try the other DNS method
        try {
            val fallbackIps = if (isUdpMode) {
                // UDP failed, try system DNS
                return Dns.SYSTEM.lookup(hostname)
            } else {
                // DoH failed, try UDP to common Chinese DNS servers
                queryUdpDns(hostname, "119.29.29.29")
            }
            if (fallbackIps.isNotEmpty()) {
                val addresses = fallbackIps.map { InetAddress.getByName(it) }
                cache[hostname] = CacheEntry(addresses, System.currentTimeMillis() + CACHE_TTL)
                return addresses
            }
        } catch (e: Exception) {
        }

        // Last resort: system DNS
        cache[hostname]?.let { return it.addresses }
        try {
            return Dns.SYSTEM.lookup(hostname)
        } catch (e2: Exception) {
        }

        throw UnknownHostException("Failed to resolve $hostname via all DNS methods")
    }

    private fun queryDoh(hostname: String): List<String> {
        val query = buildDnsQuery(hostname)
        val request = Request.Builder()
            .url(dnsServer)
            .post(query.toRequestBody("application/dns-message".toMediaType()))
            .header("Accept", "application/dns-message")
            .build()

        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UnknownHostException("DoH HTTP ${response.code}")
            }
            val body = response.body?.bytes()
                ?: throw UnknownHostException("DoH empty response")
            return parseDnsResponse(body)
        }
    }

    private fun queryUdpDns(hostname: String, serverIp: String): List<String> {
        val query = buildDnsQuery(hostname)
        val socket = DatagramSocket()
        socket.soTimeout = 4000
        try {
            val addr = InetAddress.getByName(serverIp)
            val sendPacket = DatagramPacket(query, query.size, addr, 53)
            socket.send(sendPacket)

            val recvBuffer = ByteArray(1024)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
            socket.receive(recvPacket)

            val response = recvBuffer.copyOf(recvPacket.length)
            return parseDnsResponse(response)
        } finally {
            socket.close()
        }
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeShort(Random.nextInt(65536))
        dos.writeShort(0x0100) // RD=1
        dos.writeShort(1)      // QDCOUNT
        dos.writeShort(0)      // ANCOUNT
        dos.writeShort(0)      // NSCOUNT
        dos.writeShort(0)      // ARCOUNT

        val cleanDomain = domain.trimEnd('.')
        for (part in cleanDomain.split(".")) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0) // terminator

        dos.writeShort(1) // QTYPE=A
        dos.writeShort(1) // QCLASS=IN

        return baos.toByteArray()
    }

    private fun parseDnsResponse(data: ByteArray): List<String> {
        val dis = DataInputStream(ByteArrayInputStream(data))
        val ips = mutableListOf<String>()

        dis.readShort() // ID
        val flags = dis.readShort().toInt() and 0xFFFF
        val rcode = flags and 0x000F
        val qdcount = dis.readShort().toInt() and 0xFFFF
        val ancount = dis.readShort().toInt() and 0xFFFF
        dis.readShort() // NSCOUNT
        dis.readShort() // ARCOUNT

        if (rcode != 0) {
            return emptyList()
        }

        for (i in 0 until qdcount) {
            skipName(dis)
            dis.readShort() // QTYPE
            dis.readShort() // QCLASS
        }

        for (i in 0 until ancount) {
            skipName(dis)
            val type = dis.readShort().toInt() and 0xFFFF
            dis.readShort() // CLASS
            dis.readInt()   // TTL
            val rdlength = dis.readShort().toInt() and 0xFFFF

            if (type == 1 && rdlength == 4) {
                val b1 = dis.readByte().toInt() and 0xFF
                val b2 = dis.readByte().toInt() and 0xFF
                val b3 = dis.readByte().toInt() and 0xFF
                val b4 = dis.readByte().toInt() and 0xFF
                ips.add("$b1.$b2.$b3.$b4")
            } else {
                var remaining = rdlength
                while (remaining > 0) {
                    val skipped = dis.skip(remaining.toLong()).toInt()
                    if (skipped <= 0) break
                    remaining -= skipped
                }
            }
        }

        return ips
    }

    private fun skipName(dis: DataInputStream) {
        while (true) {
            val len = dis.readByte().toInt() and 0xFF
            if (len == 0) return
            if (len and 0xC0 == 0xC0) {
                dis.readByte()
                return
            }
            dis.skipBytes(len)
        }
    }

    companion object {
        private const val CACHE_TTL = 5 * 60 * 1000L

        private val DOH_BOOTSTRAP_IPS = mapOf(
            "dns.alidns.com" to listOf("223.5.5.5", "223.6.6.6"),
            "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
            "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
            "doh.pub" to listOf("1.12.12.12", "120.53.53.53"),
            "doh.360.cn" to listOf("101.226.4.6", "218.30.118.6"),
        )

        private val IP_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

        fun isIpAddress(s: String): Boolean = IP_REGEX.matches(s)
    }

    private class BootstrapDns(private val dohHost: String) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == dohHost && dohHost.isNotEmpty()) {
                DOH_BOOTSTRAP_IPS[hostname]?.let { ips ->
                    return ips.map { InetAddress.getByName(it) }
                }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }
}
