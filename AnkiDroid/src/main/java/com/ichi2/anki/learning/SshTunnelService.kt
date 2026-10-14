// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.learning

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import timber.log.Timber
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps an SSH local port forward to the self-hosted sync server alive while AnkiDroid's normal
 * sync and media workers use it, reconnecting a few times after network changes.
 *
 * The password never travels in an Intent: [SecretHandoff] passes it in memory, and it is wiped
 * when the service stops.
 */
class SshTunnelService : Service() {
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var session: Session? = null

    @Volatile private var connecting = false

    @Volatile private var stopped = false
    private var secret: ByteArray = ByteArray(0)

    enum class State { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    data class Status(
        val state: State,
        val message: String?,
    )

    internal data class TunnelConfig(
        val host: String,
        val user: String,
        val port: Int,
        val remotePort: Int,
    ) {
        /** Identifies the remote account, so its sync login can be remembered across connections. */
        val profile get() = "$user@$host:$port>$remotePort"

        fun toIntent(context: Context) =
            Intent(context, SshTunnelService::class.java)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_USER, user)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_REMOTE_PORT, remotePort)

        companion object {
            fun fromIntent(intent: Intent): TunnelConfig? {
                val host = intent.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() } ?: return null
                val user = intent.getStringExtra(EXTRA_USER)?.takeIf { it.isNotBlank() } ?: return null
                return TunnelConfig(host, user, intent.getIntExtra(EXTRA_PORT, 22), intent.getIntExtra(EXTRA_REMOTE_PORT, 8080))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopSelf()
            return START_NOT_STICKY
        }
        val config = intent?.let { TunnelConfig.fromIntent(it) }
        val handedSecret = SecretHandoff.take()
        val alreadyRunning = session?.isConnected == true || connecting
        // startForegroundService() must always be followed by startForeground(), even when we bail out.
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.seal_ssh_notification_channel))
                .build(),
        )
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        val text = if (status.state == State.CONNECTED) R.string.seal_ssh_notification_connected else R.string.seal_ssh_status_connecting
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(getString(text)), foregroundType)
        if (alreadyRunning) {
            handedSecret?.fill(0)
            return START_NOT_STICKY
        }
        if (config == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        connecting = true
        secret = handedSecret ?: ByteArray(0)
        publish(State.CONNECTING, getString(R.string.seal_ssh_status_connecting))
        executor.execute { run(config) }
        return START_NOT_STICKY
    }

    private fun run(config: TunnelConfig) {
        try {
            var localPort = connect(config, preferredPort())
            useEndpoint(config, localPort)
            connecting = false
            // Watch the session; JSch does not reconnect on its own after a network change.
            while (true) {
                Thread.sleep(MONITOR_INTERVAL_MS)
                if (session?.isConnected == true) continue
                localPort = reconnect(config, localPort)
            }
        } catch (_: InterruptedException) {
            // Service is stopping.
        } catch (error: Exception) {
            if (!stopped) {
                Timber.w(error, "SSH tunnel failed")
                publish(State.FAILED, getString(R.string.seal_ssh_status_failed, describe(error)))
                ContextCompat.getMainExecutor(this).execute { stopSelf() }
            }
        } finally {
            connecting = false
        }
    }

    private fun reconnect(
        config: TunnelConfig,
        localPort: Int,
    ): Int {
        var lastError: Exception? = null
        RECONNECT_DELAYS_MS.forEachIndexed { index, delay ->
            publish(State.RECONNECTING, getString(R.string.seal_ssh_status_reconnecting, index + 1, RECONNECT_DELAYS_MS.size))
            Thread.sleep(delay)
            try {
                session?.disconnect()
                val port = connect(config, localPort)
                useEndpoint(config, port)
                return port
            } catch (error: JSchException) {
                lastError = error
            }
        }
        throw lastError ?: JSchException("reconnect failed")
    }

    /** Opens the session and the forward; returns the local port actually bound. */
    private fun connect(
        config: TunnelConfig,
        preferredPort: Int,
    ): Int {
        val ssh = JSch()
        val knownHosts = File(noBackupFilesDir, KNOWN_HOSTS_FILE)
        if (!knownHosts.exists()) throw JSchException(getString(R.string.seal_ssh_need_known_hosts))
        ssh.setKnownHosts(knownHosts.path)
        val keyFile = File(noBackupFilesDir, PRIVATE_KEY_FILE)
        val useKey = keyFile.exists()
        if (useKey) {
            val key = KeyVault.read(keyFile)
            try {
                ssh.addIdentity("learning-sync", key, null, secret.copyOf())
            } finally {
                key.fill(0)
            }
        }
        val connection = ssh.getSession(config.user, config.host, config.port)
        session = connection
        connection.setConfig("StrictHostKeyChecking", "yes")
        connection.setConfig("PreferredAuthentications", if (useKey) "publickey" else "password,keyboard-interactive")
        if (!useKey) {
            connection.setPassword(secret.copyOf())
            // Many servers only offer passwords through keyboard-interactive (PAM).
            connection.userInfo = PasswordPrompt(secret)
        }
        connection.serverAliveInterval = 30_000
        connection.serverAliveCountMax = 3
        connection.connect(CONNECT_TIMEOUT_MS)
        if (stopped) throw InterruptedException()
        val port =
            try {
                connection.setPortForwardingL("127.0.0.1", preferredPort, "127.0.0.1", config.remotePort)
            } catch (busy: JSchException) {
                // Another app holds the port: let the system choose, and remember it for next time.
                Timber.i(busy, "SSH tunnel: port %d busy", preferredPort)
                connection.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", config.remotePort)
            }
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit { putInt(LOCAL_PORT, port) }
        return port
    }

    private fun useEndpoint(
        config: TunnelConfig,
        port: Int,
    ) {
        synchronized(SyncEndpointSwap) {
            if (stopped) throw InterruptedException()
            SyncEndpointSwap.apply(this, "http://127.0.0.1:$port/", config.profile)
        }
        publish(State.CONNECTED, getString(R.string.seal_ssh_status_connected, port))
        updateNotification(getString(R.string.seal_ssh_notification_connected))
    }

    private fun preferredPort() = getSharedPreferences(SETTINGS, MODE_PRIVATE).getInt(LOCAL_PORT, DEFAULT_LOCAL_PORT)

    private fun describe(error: Exception): String = error.localizedMessage ?: error.javaClass.simpleName

    private fun notification(text: String) =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.seal_ssh_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, DeckPicker::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.seal_ssh_disconnect),
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, SshTunnelService::class.java).setAction(ACTION_DISCONNECT),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()

    private fun updateNotification(text: String) {
        val allowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        stopped = true
        running = false
        executor.shutdownNow()
        session?.disconnect()
        secret.fill(0)
        synchronized(SyncEndpointSwap) { SyncEndpointSwap.restore(this) }
        if (status.state != State.FAILED) publish(State.IDLE, getString(R.string.seal_ssh_status_disconnected))
        super.onDestroy()
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        publish(State.FAILED, getString(R.string.seal_ssh_status_timeout))
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "learning_ssh"
        private const val NOTIFICATION_ID = 0x5EA1
        private const val ACTION_DISCONNECT = "com.ichi2.anki.learning.DISCONNECT"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_USER = "user"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_REMOTE_PORT = "remotePort"
        internal const val SETTINGS = "learning_ssh"
        private const val LOCAL_PORT = "localPort"
        private const val DEFAULT_LOCAL_PORT = 18765
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val MONITOR_INTERVAL_MS = 15_000L
        private val RECONNECT_DELAYS_MS = listOf(2_000L, 10_000L, 30_000L)
        internal const val PRIVATE_KEY_FILE = "learning-private-key.enc"
        internal const val KNOWN_HOSTS_FILE = "learning-known-hosts"

        /** True while a service instance exists in this process. */
        @Volatile var running = false
            private set

        @Volatile var status = Status(State.IDLE, null)
            private set

        private fun publish(
            state: State,
            message: String?,
        ) {
            status = Status(state, message)
        }

        fun disconnect(context: Context) {
            context.stopService(Intent(context, SshTunnelService::class.java))
        }
    }
}

/** Answers keyboard-interactive password prompts; never shows UI and never trusts unknown hosts. */
private class PasswordPrompt(
    private val secret: ByteArray,
) : UserInfo,
    UIKeyboardInteractive {
    override fun getPassphrase(): String? = null

    override fun getPassword(): String = String(secret, Charsets.UTF_8)

    override fun promptPassword(message: String?) = secret.isNotEmpty()

    override fun promptPassphrase(message: String?) = false

    override fun promptYesNo(message: String?) = false

    override fun showMessage(message: String?) = Unit

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?,
    ): Array<String>? {
        if (prompt == null || secret.isEmpty()) return null
        // Only answer hidden prompts (passwords); anything else (e.g. OTP with echo) is declined.
        if (echo != null && echo.any { it }) return null
        return Array(prompt.size) { String(secret, Charsets.UTF_8) }
    }
}

/** Hands the password to the service in memory, so it is never written into an Intent or the activity's saved state. */
internal object SecretHandoff {
    private var secret: ByteArray? = null

    @Synchronized
    fun put(value: ByteArray) {
        secret?.fill(0)
        secret = value
    }

    @Synchronized
    fun take(): ByteArray? = secret.also { secret = null }
}

/** Device-bound encrypted key, excluded from Android cloud/device backups. */
internal object KeyVault {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("learning-ssh", null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder("learning-ssh", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
    }

    fun write(
        file: File,
        plain: ByteArray,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        file.writeBytes(cipher.iv + cipher.doFinal(plain))
    }

    fun read(file: File): ByteArray {
        val encrypted = file.readBytes()
        require(encrypted.size > 28) { "corrupt key file" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
    }
}
