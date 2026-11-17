package com.cfbench

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Xray 内核封装。
 *
 * Android 10 起普通应用不能执行私有目录里的文件（W^X），
 * 所以内核以 libxray.so 的名字放在 jniLibs 里，安装时由系统释放到
 * nativeLibraryDir —— 那个目录只读且可执行，正好符合限制。
 */
class XrayRunner(private val ctx: Context) {

    private var proc: Process? = null

    val binary: File
        get() = File(ctx.applicationInfo.nativeLibraryDir, "libxray.so")

    private val cfgFile: File
        get() = File(ctx.filesDir, "xray_run.json")

    /**
     * 只判断文件存在。不要用 canExecute()：nativeLibraryDir 受 SELinux 管辖，
     * Java 层对它经常返回 false，但实际是可以 exec 的。
     */
    fun available(): Boolean = binary.exists()

    /** 按节点生成 Xray 配置 */
    fun configFor(node: VlessNode, socksPort: Int): String {
        val p = node.params
        val net = node.proto
        val sec = (p["security"] ?: "none").lowercase()

        val user = JSONObject()
            .put("id", node.uuid)
            .put("encryption", p["encryption"] ?: "none")
        p["flow"]?.let { if (it.isNotBlank()) user.put("flow", it) }

        val stream = JSONObject().put("network", net)

        if (sec == "tls" || sec == "reality") {
            val sni = p["sni"] ?: p["peer"] ?: p["host"] ?: node.server
            if (sec == "reality") {
                stream.put("security", "reality")
                stream.put(
                    "realitySettings", JSONObject()
                        .put("serverName", sni)
                        .put("fingerprint", p["fp"] ?: "chrome")
                        .put("publicKey", p["pbk"] ?: "")
                        .put("shortId", p["sid"] ?: "")
                        .put("spiderX", p["spx"] ?: "/")
                )
            } else {
                val tls = JSONObject().put("serverName", sni)
                p["fp"]?.let { tls.put("fingerprint", it) }
                p["alpn"]?.let { a ->
                    val arr = JSONArray()
                    a.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        .forEach { arr.put(it) }
                    if (arr.length() > 0) tls.put("alpn", arr)
                }
                stream.put("security", "tls")
                stream.put("tlsSettings", tls)
            }
        }

        val path = p["path"] ?: "/"
        val host = p["host"] ?: p["sni"] ?: ""

        when (net) {
            "xhttp", "splithttp" -> {
                val xs = JSONObject().put("path", path).put("mode", p["mode"] ?: "auto")
                if (host.isNotEmpty()) xs.put("host", host)
                p["extra"]?.let { e ->
                    try {
                        xs.put("extra", JSONObject(e))
                    } catch (ignore: Exception) {
                    }
                }
                stream.put("xhttpSettings", xs)
            }
            "ws" -> {
                val ws = JSONObject().put("path", path)
                if (host.isNotEmpty()) xsHeaders(ws, host)
                stream.put("wsSettings", ws)
            }
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject().put("serviceName", p["serviceName"] ?: path.trim('/'))
            )
            "httpupgrade" -> {
                val hu = JSONObject().put("path", path)
                if (host.isNotEmpty()) hu.put("host", host)
                stream.put("httpupgradeSettings", hu)
            }
        }

        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "error"))
        root.put(
            "inbounds", JSONArray().put(
                JSONObject()
                    .put("tag", "socks-in")
                    .put("port", socksPort)
                    .put("listen", "127.0.0.1")
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", false))
            )
        )
        root.put(
            "outbounds", JSONArray()
                .put(
                    JSONObject()
                        .put("tag", "proxy")
                        .put("protocol", "vless")
                        .put(
                            "settings", JSONObject().put(
                                "vnext", JSONArray().put(
                                    JSONObject()
                                        .put("address", node.server)
                                        .put("port", node.port)
                                        .put("users", JSONArray().put(user))
                                )
                            )
                        )
                        .put("streamSettings", stream)
                )
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        )
        return root.toString(2)
    }

    private fun xsHeaders(ws: JSONObject, host: String) {
        ws.put("headers", JSONObject().put("Host", host))
    }

    /** 启动内核并等待 socks 端口就绪 */
    fun start(node: VlessNode, socksPort: Int): Boolean {
        stop()
        return try {
            cfgFile.writeText(configFor(node, socksPort))
            val pb = ProcessBuilder(binary.absolutePath, "run", "-c", cfgFile.absolutePath)
            pb.redirectErrorStream(true)
            proc = pb.start()
            waitPort(socksPort, 6000)
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        val p = proc ?: return
        try {
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        } catch (e: Exception) {
            try {
                p.destroyForcibly()
            } catch (ignore: Exception) {
            }
        }
        proc = null
    }

    private fun waitPort(port: Int, timeoutMs: Int): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                return true
            } catch (e: Exception) {
                Thread.sleep(100)
            }
        }
        return false
    }
}
