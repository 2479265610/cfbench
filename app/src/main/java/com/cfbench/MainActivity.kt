package com.cfbench

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var runner: XrayRunner
    private lateinit var bench: BenchRunner

    private val logBuf = StringBuilder()
    private var running = false

    private val defaultBase = "https://drive-01.bacloud.online/remote.php/dav/files/2479265610%40qq.com/cfbench"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfbench", MODE_PRIVATE)
        runner = XrayRunner(this)
        bench = BenchRunner(runner)

        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (28 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }

        val title = TextView(this).apply {
            text = "CF 节点测速"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF16202E.toInt())
        }
        root.addView(title)

        statusView = TextView(this).apply {
            text = if (runner.available()) "内核就绪" else "内核缺失（libxray.so 未找到）"
            textSize = 12f
            setTextColor(if (runner.available()) 0xFF16794F.toInt() else 0xFFC0392B.toInt())
            setPadding(0, (4 * dp).toInt(), 0, (12 * dp).toInt())
        }
        root.addView(statusView)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        startBtn = Button(this).apply {
            text = "开始测速"
            setOnClickListener { start() }
        }
        btnRow.addView(startBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val cfgBtn = Button(this).apply {
            text = "设置"
            setOnClickListener { showSettings() }
        }
        btnRow.addView(cfgBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val clearBtn = Button(this).apply {
            text = "清空"
            setOnClickListener {
                logBuf.setLength(0)
                logView.text = ""
            }
        }
        btnRow.addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(btnRow)

        val scroll = ScrollView(this).apply {
            setPadding(0, (10 * dp).toInt(), 0, 0)
        }
        logView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFF16202E.toInt())
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        scroll.addView(logView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        if (!runner.available()) {
            append("[!] 内核缺失。libxray.so 应位于:\n    " + runner.binary.absolutePath)
        }
        if (prefs.getString("subs", "").isNullOrBlank()) {
            append("点「设置」填入订阅地址后开始。")
        }
    }

    private fun append(line: String) {
        runOnUiThread {
            logBuf.append(line).append('\n')
            logView.text = logBuf.toString()
        }
    }

    private fun start() {
        if (running) return
        val cfg = readConfig()
        if (cfg.subs.isEmpty()) {
            append("[!] 还没有订阅地址，点「设置」填一下")
            return
        }
        running = true
        startBtn.isEnabled = false
        startBtn.text = "运行中..."
        logBuf.setLength(0)
        logView.text = ""

        lifecycleScope.launch {
            try {
                bench.run(cfg) { line -> append(line) }
            } catch (e: Exception) {
                append("[!] 中断: ${e.message}")
            } finally {
                runner.stop()
                running = false
                startBtn.isEnabled = true
                startBtn.text = "开始测速"
            }
        }
    }

    private fun readConfig(): AppConfig {
        val subs = (prefs.getString("subs", "") ?: "")
            .lines().map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
        return AppConfig(
            subs = subs,
            top = prefs.getInt("top", 10),
            maxTtfb = prefs.getFloat("maxTtfb", 3.0f).toDouble(),
            wdBase = prefs.getString("wdBase", defaultBase) ?: defaultBase,
            wdUser = prefs.getString("wdUser", "2479265610@qq.com") ?: "",
            wdPass = prefs.getString("wdPass", "") ?: "",
            wdSub = prefs.getString("wdSub", "sub-phone") ?: "sub-phone"
        )
    }

    private fun showSettings() {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), (8 * dp).toInt())
        }

        fun field(label: String, value: String, multiline: Boolean = false, secret: Boolean = false): EditText {
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                setPadding(0, (10 * dp).toInt(), 0, (2 * dp).toInt())
            }
            box.addView(tv)
            val et = EditText(this).apply {
                setText(value)
                textSize = 13f
                if (multiline) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 3
                    gravity = Gravity.TOP
                }
                if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            box.addView(et)
            return et
        }

        val subsEt = field("订阅地址（一行一个）", prefs.getString("subs", "") ?: "", multiline = true)
        val baseEt = field("WebDAV 根地址", prefs.getString("wdBase", defaultBase) ?: defaultBase)
        val userEt = field("WebDAV 用户名", prefs.getString("wdUser", "2479265610@qq.com") ?: "")
        val passEt = field("WebDAV 应用密码", prefs.getString("wdPass", "") ?: "", secret = true)
        val subEt = field("云端子目录", prefs.getString("wdSub", "sub-phone") ?: "sub-phone")
        val topEt = field("保留几个最快节点", prefs.getInt("top", 10).toString())

        val scroll = ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putString("subs", subsEt.text.toString())
                    .putString("wdBase", baseEt.text.toString().trim())
                    .putString("wdUser", userEt.text.toString().trim())
                    .putString("wdPass", passEt.text.toString().trim())
                    .putString("wdSub", subEt.text.toString().trim())
                    .putInt("top", topEt.text.toString().toIntOrNull() ?: 10)
                    .apply()
                append("设置已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
