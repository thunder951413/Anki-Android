// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.learning

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.ichi2.anki.AnkiActivity
import java.io.File

class SshSettingsActivity : AnkiActivity() {
    private lateinit var host: EditText
    private lateinit var user: EditText
    private lateinit var port: EditText
    private lateinit var remotePort: EditText
    private lateinit var secret: EditText
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val updateStatus = object : Runnable {
        override fun run() { status.text = SshTunnelService.status; handler.postDelayed(this, 1000) }
    }
    private val importKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("文件无法读取")
                require(bytes.size in 1..100_000) { "私钥文件大小无效" }
                try { KeyVault.write(File(noBackupFilesDir, "learning-private-key.enc"), bytes) } finally { bytes.fill(0) }
            }.onSuccess { toast("私钥已加密保存在本机") }.onFailure { toast(it.localizedMessage ?: "导入失败") }
        }
    }
    private val importHosts = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("文件无法读取")
            require(bytes.size in 1..1_000_000) { "known_hosts 文件大小无效" }
            File(noBackupFilesDir, "learning-known-hosts").writeBytes(bytes)
        }.onSuccess { toast("服务器身份文件已导入") }.onFailure { toast(it.localizedMessage ?: "导入失败") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = getSharedPreferences("learning_ssh", MODE_PRIVATE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        setContentView(ScrollView(this).apply { addView(layout) })
        layout.addView(TextView(this).apply { text = "SSH 同步连接"; textSize = 24f })
        layout.addView(TextView(this).apply { text = "同步连接会保留在通知栏。连接成功后，返回牌组登录你的自建同步账号；电脑端连接同一服务器。"; setPadding(0, 20, 0, 20) })
        fun field(label: String, value: String): EditText {
            layout.addView(TextView(this).apply { text = label })
            return EditText(this).apply { setText(value); isSingleLine = true; layout.addView(this) }
        }
        host = field("服务器地址", settings.getString("host", "").orEmpty())
        user = field("SSH 用户名", settings.getString("user", "").orEmpty())
        port = field("SSH 端口", settings.getString("port", "22").orEmpty()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        remotePort = field("服务器本机同步服务端口", settings.getString("remotePort", "8080").orEmpty()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        secret = field("SSH 密码或私钥口令（本次连接使用）", "").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(Button(this).apply { text = "导入 SSH 私钥"; setOnClickListener { importKey.launch(arrayOf("*/*")) } })
        layout.addView(Button(this).apply { text = "导入电脑已核对的 known_hosts"; setOnClickListener { importHosts.launch(arrayOf("*/*")) } })
        layout.addView(Button(this).apply { text = "删除本机私钥，改用密码"; setOnClickListener { File(noBackupFilesDir, "learning-private-key.enc").delete(); toast("本机私钥已删除") } })
        layout.addView(Button(this).apply { text = "连接"; setOnClickListener {
            try {
                require(host.text.isNotBlank() && user.text.isNotBlank()) { "请填写服务器和用户名" }
                val sshPort = port.text.toString().toInt()
                val servicePort = remotePort.text.toString().toInt()
                require(sshPort in 1..65535 && servicePort in 1..65535) { "端口需在 1–65535 之间" }
                settings.edit { putString("host", host.text.toString().trim()); putString("user", user.text.toString().trim()); putString("port", sshPort.toString()); putString("remotePort", servicePort.toString()) }
                ContextCompat.startForegroundService(this@SshSettingsActivity, Intent(this@SshSettingsActivity, SshTunnelService::class.java).apply {
                    putExtra("host", host.text.toString().trim()); putExtra("user", user.text.toString().trim()); putExtra("port", sshPort); putExtra("remotePort", servicePort); putExtra("secret", secret.text.toString())
                })
                secret.text.clear()
            } catch (error: Exception) { toast(error.localizedMessage ?: "连接失败") }
        } })
        layout.addView(Button(this).apply { text = "断开"; setOnClickListener { stopService(Intent(this@SshSettingsActivity, SshTunnelService::class.java)); SshTunnelService.status = "已断开" } })
        status = TextView(this).apply { setPadding(0, 24, 0, 0) }
        layout.addView(status)
        handler.post(updateStatus)
    }

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onDestroy() { handler.removeCallbacks(updateStatus); super.onDestroy() }
}
