package com.cfbench

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class AppConfig(
    val subs: List<String>,
    val top: Int = 10,
    val maxTtfb: Double = 3.0,
    val socksPort: Int = 10809,
    val wdBase: String = "",
    val wdUser: String = "",
    val wdPass: String = "",
    val wdSub: String = ""
)

data class BenchItem(
    val node: VlessNode,
    var ttfb: Double? = null,
    var speed: Double? = null
)

class BenchRunner(private val runner: XrayRunner) {

    private val probeUrl = "https://www.gstatic.com/generate_204"
    private val dlUrl = "http://cachefly.cachefly.net/1mb.test"

    private val fetchClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 完整流程：拉订阅 -> 逐节点测首字节 -> 筛选 -> 上传 */
    suspend fun run(cfg: AppConfig, onLog: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!runner.available()) {
            onLog("[!] 内核缺失：nativeLibraryDir 里没有 libxray.so")
            return@withContext false
        }
        if (cfg.subs.isEmpty()) {
            onLog("[!] 没有订阅地址")
            return@withContext false
        }

        onLog("[1/4] 拉取订阅")
        val nodes = mutableListOf<VlessNode>()
        val seen = HashSet<String>()
        for (url in cfg.subs) {
            val host = try {
                java.net.URI(url).host ?: url
            } catch (e: Exception) {
                url
            }
            try {
                val raw = fetch(url)
                val links = SubParser.decode(raw)
                var added = 0
                for (l in links) {
                    val n = SubParser.parseVless(l) ?: continue
                    val key = "${n.server}:${n.port}:${n.uuid}:${n.params["path"] ?: ""}"
                    if (seen.add(key)) {
                        nodes.add(n)
                        added++
                    }
                }
                onLog("      $host -> ${links.size} 个链接，新增 $added")
            } catch (e: Exception) {
                onLog("      $host 失败: ${e.message?.take(50)}")
            }
        }
        if (nodes.isEmpty()) {
            onLog("[!] 没有拿到任何节点")
            return@withContext false
        }

        onLog("")
        onLog("[2/4] 实测 ${nodes.size} 个节点的首字节")
        val items = ArrayList<BenchItem>()
        nodes.forEachIndexed { idx, n ->
            val item = BenchItem(n)
            if (runner.start(n, cfg.socksPort)) {
                item.ttfb = probe(cfg.socksPort)
            }
            runner.stop()
            items.add(item)
            val mark = item.ttfb?.let { String.format("%5.2fs", it) } ?: "  --  "
            onLog(String.format("      %2d/%d  %-34s %-6s %s",
                idx + 1, nodes.size, n.name.take(32), n.proto, mark))
        }

        val alive = items.filter { it.ttfb != null }.sortedBy { it.ttfb }
        val qualified = alive.filter { (it.ttfb ?: 99.0) <= cfg.maxTtfb }
        val picked = (if (qualified.isNotEmpty()) qualified else alive)
            .take(if (cfg.top > 0) cfg.top else 5)

        onLog("")
        onLog("[3/4] 通 ${alive.size}/${items.size}，首字节<=${cfg.maxTtfb}s 的 ${qualified.size} 个，取前 ${picked.size}")

        if (picked.isEmpty()) {
            onLog("[!] 没有可用节点")
            return@withContext false
        }

        for (p in picked) {
            if (runner.start(p.node, cfg.socksPort)) {
                p.speed = download(cfg.socksPort)
            }
            runner.stop()
            val s = p.speed?.let { String.format("%5.2f MB/s", it) } ?: "   --   "
            onLog(String.format("      %-34s %5.2fs  %s",
                p.node.name.take(32), p.ttfb ?: 0.0, s))
        }

        onLog("")
        onLog("[4/4] 上传")
        val plain = picked.joinToString("\n") { it.node.link } + "\n"
        val b64 = android.util.Base64.encodeToString(
            plain.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        uploadAll(cfg, plain, b64, onLog)
        return@withContext true
    }

    fun sortedResult(items: List<BenchItem>): List<BenchItem> =
        items.filter { it.ttfb != null }.sortedBy { it.ttfb }

    // ---------------------------------------------------------------- HTTP

    private fun fetch(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/122.0 Safari/537.36")
            .build()
        fetchClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    private fun benchClient(port: Int): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    private fun probe(port: Int): Double? {
        return try {
            val client = benchClient(port)
            val req = Request.Builder().url(probeUrl).build()
            val t0 = System.nanoTime()
            client.newCall(req).execute().use { resp ->
                val sec = (System.nanoTime() - t0) / 1e9
                if (resp.code == 204) sec else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun download(port: Int): Double? {
        return try {
            val client = benchClient(port)
            val req = Request.Builder().url(dlUrl).build()
            val t0 = System.currentTimeMillis()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                if (bytes.isEmpty() || secs <= 0.0) return null
                (bytes.size / 1048576.0) / secs
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- 上传

    private fun uploadAll(cfg: AppConfig, plain: String, b64: String, onLog: (String) -> Unit) {
        if (cfg.wdBase.isBlank() || cfg.wdUser.isBlank() || cfg.wdPass.isBlank()) {
            onLog("      WebDAV 未配置，跳过上传（结果只在本机）")
            return
        }
        val base = cfg.wdBase.trimEnd('/')
        val sub = cfg.wdSub.trim('/')
        var dir = base
        if (sub.isNotEmpty()) {
            sub.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                dir += "/" + URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
                dav(dir, "MKCOL", null, cfg)
            }
        }
        val c1 = dav("$dir/sub.txt", "PUT", b64.toByteArray(Charsets.UTF_8), cfg)
        val c2 = dav("$dir/best.txt", "PUT", plain.toByteArray(Charsets.UTF_8), cfg)
        onLog("      sub.txt -> $c1, best.txt -> $c2")

        val rel = listOf(
            Regex("^/remote\\.php/dav/files/[^/]+/?(.*)$")
                .find(java.net.URI(base).path ?: "")?.groupValues?.get(1)?.trim('/') ?: "",
            sub, "sub.txt"
        ).filter { it.isNotEmpty() }.joinToString("/")

        val share = ensureShare(base, rel, cfg)
        if (share != null) {
            onLog("")
            onLog("  Karing 订阅地址:")
            onLog("  $share/download")
        } else {
            onLog("      [!] 没能创建公开分享，请在 Nextcloud 里手动分享该文件")
        }
    }

    private fun dav(url: String, method: String, body: ByteArray?, cfg: AppConfig): Int {
        return try {
            val rb = body?.toRequestBody("text/plain; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(url)
                .method(method, rb)
                .header("Authorization", Credentials.basic(cfg.wdUser, cfg.wdPass))
                .build()
            uploadClient.newCall(req).execute().use { it.code }
        } catch (e: Exception) {
            -1
        }
    }

    private fun ensureShare(base: String, relPath: String, cfg: AppConfig): String? {
        return try {
            val uri = java.net.URI(base)
            val origin = "${uri.scheme}://${uri.host}"
            val api = "$origin/ocs/v2.php/apps/files_sharing/api/v1/shares"
            val cred = Credentials.basic(cfg.wdUser, cfg.wdPass)

            val qUrl = "$api?path=" + URLEncoder.encode(relPath, "UTF-8").replace("+", "%20")
            val qReq = Request.Builder().url(qUrl)
                .header("Authorization", cred)
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .build()
            uploadClient.newCall(qReq).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                val ocs = JSONObject(txt).optJSONObject("ocs") ?: return null
                val meta = ocs.optJSONObject("meta")
                if (meta?.optString("status") == "ok") {
                    val arr = ocs.optJSONArray("data")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val s = arr.optJSONObject(i) ?: continue
                            if (s.optInt("share_type") == 3) {
                                val u = s.optString("url")
                                if (u.isNotEmpty()) return u
                            }
                        }
                    }
                }
            }

            val form = "path=" + URLEncoder.encode(relPath, "UTF-8").replace("+", "%20") +
                    "&shareType=3&permissions=1"
            val pReq = Request.Builder().url(api)
                .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .header("Authorization", cred)
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .build()
            uploadClient.newCall(pReq).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                val ocs = JSONObject(txt).optJSONObject("ocs") ?: return null
                if (ocs.optJSONObject("meta")?.optString("status") == "ok") {
                    ocs.optJSONObject("data")?.optString("url")
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
