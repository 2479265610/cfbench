package com.cfbench

import android.util.Base64
import java.net.URLDecoder

data class VlessNode(
    val link: String,
    val name: String,
    val server: String,
    val port: Int,
    val uuid: String,
    val params: Map<String, String>
) {
    val proto: String get() = params["type"] ?: "tcp"
}

object SubParser {

    /** 把订阅原文解成链接列表，兼容 base64 订阅体和明文链接列表 */
    fun decode(raw: String): List<String> {
        val s = raw.trim()
        if (s.contains("://")) {
            return s.lines().map { it.trim() }.filter { it.contains("://") }
        }
        val compact = s.replace(Regex("\\s+"), "")
        if (compact.length > 40 && compact.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
            return try {
                val dec = String(Base64.decode(compact, Base64.DEFAULT), Charsets.UTF_8)
                dec.lines().map { it.trim() }.filter { it.contains("://") }
            } catch (e: Exception) {
                emptyList()
            }
        }
        return emptyList()
    }

    fun parseVless(link: String): VlessNode? {
        if (!link.startsWith("vless://")) return null
        val rest = link.substring(8)

        var body = rest
        var name = ""
        val hash = rest.lastIndexOf('#')
        if (hash >= 0) {
            body = rest.substring(0, hash)
            name = dec(rest.substring(hash + 1))
        }

        var main = body
        var query = ""
        val qi = body.indexOf('?')
        if (qi >= 0) {
            main = body.substring(0, qi)
            query = body.substring(qi + 1)
        }

        val at = main.lastIndexOf('@')
        if (at < 0) return null
        val uuid = dec(main.substring(0, at))
        val hostPort = main.substring(at + 1)

        var server: String
        var portStr: String
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            server = hostPort.substring(1, close)
            portStr = hostPort.substring(close + 1).removePrefix(":")
        } else {
            val ci = hostPort.lastIndexOf(':')
            if (ci < 0) return null
            server = hostPort.substring(0, ci)
            portStr = hostPort.substring(ci + 1)
        }
        val port = portStr.toIntOrNull() ?: 443

        val params = HashMap<String, String>()
        if (query.isNotEmpty()) {
            query.split('&').forEach { kv ->
                val eq = kv.indexOf('=')
                if (eq >= 0) {
                    params[kv.substring(0, eq)] = dec(kv.substring(eq + 1))
                } else if (kv.isNotEmpty()) {
                    params[kv] = ""
                }
            }
        }

        return VlessNode(
            link = link,
            name = if (name.isNotEmpty()) name else "$server:$port",
            server = server,
            port = port,
            uuid = uuid,
            params = params
        )
    }

    private fun dec(v: String): String = try {
        URLDecoder.decode(v, "UTF-8")
    } catch (e: Exception) {
        v
    }
}
