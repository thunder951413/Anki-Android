// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.learning

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.settings.Prefs
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the tunnel alive while AnkiDroid's existing sync/media workers run. */
class SshTunnelService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var session: Session? = null
    @Volatile private var connecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "disconnect") { stopSelf(); return START_NOT_STICKY }
        if (intent == null) return START_NOT_STICKY
        if (session?.isConnected == true || connecting) return START_NOT_STICKY
        connecting = true
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("learning_ssh", "SSH 同步连接", NotificationManager.IMPORTANCE_LOW))
        startForeground(18765, notification("正在连接同步服务器…"))
        status = "正在连接…"
        executor.execute {
            try {
                val host = intent.getStringExtra("host") ?: error("缺少服务器")
                val user = intent.getStringExtra("user") ?: error("缺少用户名")
                val secret = intent.getStringExtra("secret").orEmpty()
                val ssh = JSch()
                val knownHosts = File(noBackupFilesDir, "learning-known-hosts")
                require(knownHosts.exists()) { "请先导入经过核对的 known_hosts 文件。" }
                ssh.setKnownHosts(knownHosts.path)
                val keyFile = File(noBackupFilesDir, "learning-private-key.enc")
                if (keyFile.exists()) {
                    val key = KeyVault.read(keyFile)
                    try { ssh.addIdentity("learning-sync", key, null, secret.toByteArray()) }
                    finally { key.fill(0) }
                }
                val connection = ssh.getSession(user, host, intent.getIntExtra("port", 22))
                session = connection
                connection.setConfig("StrictHostKeyChecking", "yes")
                connection.setConfig("PreferredAuthentications", if (keyFile.exists()) "publickey" else "password,keyboard-interactive")
                if (!keyFile.exists()) connection.setPassword(secret)
                connection.serverAliveInterval = 30_000
                connection.serverAliveCountMax = 3
                connection.connect(20_000)
                check(!executor.isShutdown) { "连接已取消" }
                val remotePort = intent.getIntExtra("remotePort", 8080)
                connection.setPortForwardingL("127.0.0.1", 18765, "127.0.0.1", remotePort)
                val endpoint = "http://127.0.0.1:18765/"
                val previous = Prefs.customSyncUri
                sharedPrefs().edit {
                    putString(getString(R.string.custom_sync_server_collection_url_key), endpoint)
                    putBoolean(getString(R.string.custom_sync_server_enabled_key), true)
                }
                Prefs.currentSyncUri = null
                if (previous != endpoint) Prefs.hkey = null
                status = "已连接。返回牌组，登录自建同步账号并同步。"
                manager.notify(18765, notification("已连接 · 点击返回 AnkiDroid 同步"))
            } catch (error: Exception) {
                status = "连接失败：${error.localizedMessage}"
                session?.disconnect()
                stopSelf()
            } finally { connecting = false }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, "learning_ssh")
        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
        .setContentTitle("小海豹 · SSH 同步")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, DeckPicker::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开", PendingIntent.getService(this, 1, Intent(this, SshTunnelService::class.java).setAction("disconnect"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    override fun onDestroy() {
        executor.shutdownNow()
        session?.disconnect()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        status = "同步连接已到系统时限，请重新连接。"
        stopSelf()
    }

    companion object {
        @Volatile var status: String = "尚未连接"
    }
}

/** Device-bound encrypted key, excluded from Android cloud/device backups. */
internal object KeyVault {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("learning-ssh", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("learning-ssh", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
    fun write(file: File, plain: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        file.writeBytes(cipher.iv + cipher.doFinal(plain))
    }
    fun read(file: File): ByteArray {
        val encrypted = file.readBytes()
        require(encrypted.size > 28) { "私钥文件损坏，请重新导入。" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
    }
}
