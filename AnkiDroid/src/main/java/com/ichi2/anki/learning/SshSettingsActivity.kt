// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.learning

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import java.io.File

/**
 * Settings screen for the SSH sync tunnel. Written for a parent setting things up once: the
 * steps are on screen, file status is visible, and the password is never stored.
 */
class SshSettingsActivity : AnkiActivity() {
    private lateinit var host: EditText
    private lateinit var user: EditText
    private lateinit var port: EditText
    private lateinit var remotePort: EditText
    private lateinit var secret: EditText
    private lateinit var status: TextView
    private lateinit var files: TextView
    private lateinit var connect: MaterialButton
    private lateinit var disconnect: MaterialButton
    private val handler = Handler(Looper.getMainLooper())
    private var lastStatus: SshTunnelService.Status? = null

    private val updateStatus =
        object : Runnable {
            override fun run() {
                renderStatus()
                handler.postDelayed(this, 1000)
            }
        }

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val importKey =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                val bytes =
                    contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error(getString(R.string.seal_ssh_file_unreadable))
                try {
                    require(bytes.size in 1..100_000) { getString(R.string.seal_ssh_invalid_key) }
                    KeyVault.write(File(noBackupFilesDir, SshTunnelService.PRIVATE_KEY_FILE), bytes)
                } finally {
                    bytes.fill(0)
                }
            }.onSuccess { toast(getString(R.string.seal_ssh_key_saved)) }
                .onFailure { toast(getString(R.string.seal_ssh_import_failed, it.localizedMessage ?: "")) }
            renderFiles()
        }

    private val importHosts =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                val bytes =
                    contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error(getString(R.string.seal_ssh_file_unreadable))
                require(bytes.size in 1..1_000_000 && looksLikeKnownHosts(String(bytes, Charsets.UTF_8))) {
                    getString(R.string.seal_ssh_invalid_hosts)
                }
                File(noBackupFilesDir, SshTunnelService.KNOWN_HOSTS_FILE).writeBytes(bytes)
            }.onSuccess { toast(getString(R.string.seal_ssh_hosts_saved)) }
                .onFailure { toast(getString(R.string.seal_ssh_import_failed, it.localizedMessage ?: "")) }
            renderFiles()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        SyncEndpointSwap.restoreIfOrphaned(this)
        title = getString(R.string.seal_ssh_title)
        val settings = getSharedPreferences(SshTunnelService.SETTINGS, MODE_PRIVATE)
        val gap = dp(16)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll =
            ScrollView(this).apply {
                addView(layout)
                clipToPadding = false
            }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
                )
            view.updatePadding(left = bars.left + gap, top = bars.top + gap, right = bars.right + gap, bottom = bars.bottom + gap)
            insets
        }

        layout.addView(text(getString(R.string.seal_ssh_title), 24f, bold = true))
        status =
            text("", 16f).apply {
                setPadding(0, dp(8), 0, dp(8))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        layout.addView(status)
        layout.addView(text(getString(R.string.seal_ssh_intro), 15f).apply { alpha = 0.8f })

        layout.addView(section(getString(R.string.seal_ssh_setup_title)))
        layout.addView(text(getString(R.string.seal_ssh_setup_steps), 15f).apply { setTextIsSelectable(true) })

        fun field(
            label: Int,
            value: String,
            type: Int,
        ): EditText {
            val id = View.generateViewId()
            layout.addView(text(getString(label), 14f).apply { labelFor = id })
            return EditText(this).apply {
                this.id = id
                setText(value)
                isSingleLine = true
                inputType = type
                layout.addView(this)
            }
        }
        host =
            field(
                R.string.seal_ssh_host,
                settings.getString("host", "").orEmpty(),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            )
        user =
            field(
                R.string.seal_ssh_user,
                settings.getString("user", "").orEmpty(),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            )
        port = field(R.string.seal_ssh_port, settings.getString("port", "22").orEmpty(), InputType.TYPE_CLASS_NUMBER)
        remotePort = field(R.string.seal_ssh_remote_port, settings.getString("remotePort", "8080").orEmpty(), InputType.TYPE_CLASS_NUMBER)
        secret = field(R.string.seal_ssh_secret, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        layout.addView(section(getString(R.string.seal_ssh_files)))
        files = text("", 15f)
        layout.addView(files)
        layout.addView(button(R.string.seal_ssh_import_hosts, outlined = true) { importHosts.launch(arrayOf("*/*")) })
        layout.addView(button(R.string.seal_ssh_import_key, outlined = true) { importKey.launch(arrayOf("*/*")) })
        layout.addView(
            button(R.string.seal_ssh_delete_key, outlined = true) {
                AlertDialog
                    .Builder(this)
                    .setMessage(R.string.seal_ssh_delete_key_confirm)
                    .setPositiveButton(R.string.seal_ssh_delete_key) { _, _ ->
                        File(noBackupFilesDir, SshTunnelService.PRIVATE_KEY_FILE).delete()
                        toast(getString(R.string.seal_ssh_key_deleted))
                        renderFiles()
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
        )

        connect = button(R.string.seal_ssh_connect, outlined = false) { onConnect(settings) }
        disconnect =
            button(R.string.seal_ssh_disconnect, outlined = true) {
                SshTunnelService.disconnect(this)
                renderStatus()
            }
        layout.addView(connect.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24) })
        layout.addView(disconnect)
        renderFiles()
    }

    private fun onConnect(settings: android.content.SharedPreferences) {
        try {
            val hostName = host.text.toString().trim()
            val userName = user.text.toString().trim()
            require(hostName.isNotEmpty() && userName.isNotEmpty()) { getString(R.string.seal_ssh_fill_required) }
            val sshPort = port.text.toString().toIntOrNull()
            val servicePort = remotePort.text.toString().toIntOrNull()
            require(sshPort in 1..65535 && servicePort in 1..65535) { getString(R.string.seal_ssh_port_range) }
            require(File(noBackupFilesDir, SshTunnelService.KNOWN_HOSTS_FILE).exists()) { getString(R.string.seal_ssh_need_known_hosts) }
            val hasKey = File(noBackupFilesDir, SshTunnelService.PRIVATE_KEY_FILE).exists()
            require(hasKey || secret.text.isNotEmpty()) { getString(R.string.seal_ssh_need_secret) }
            settings.edit {
                putString("host", hostName)
                putString("user", userName)
                putString("port", sshPort.toString())
                putString("remotePort", servicePort.toString())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            SecretHandoff.put(secret.text.toString().toByteArray(Charsets.UTF_8))
            secret.text.clear()
            ContextCompat.startForegroundService(
                this,
                SshTunnelService.TunnelConfig(hostName, userName, sshPort!!, servicePort!!).toIntent(this),
            )
            renderStatus()
        } catch (error: Exception) {
            toast(error.localizedMessage ?: getString(R.string.seal_learning_error_generic))
        }
    }

    private fun renderStatus() {
        val current = SshTunnelService.status
        val busy = SshTunnelService.running
        connect.isEnabled = !busy
        disconnect.isEnabled = busy
        if (current == lastStatus) return
        lastStatus = current
        status.text = current.message ?: getString(R.string.seal_ssh_status_idle)
        val colorAttr =
            when (current.state) {
                SshTunnelService.State.CONNECTED -> androidx.appcompat.R.attr.colorPrimary
                SshTunnelService.State.FAILED -> androidx.appcompat.R.attr.colorError
                else -> null
            }
        status.setTextColor(if (colorAttr != null) MaterialColors.getColor(status, colorAttr) else files.currentTextColor)
    }

    private fun renderFiles() {
        val hasHosts = File(noBackupFilesDir, SshTunnelService.KNOWN_HOSTS_FILE).exists()
        val hasKey = File(noBackupFilesDir, SshTunnelService.PRIVATE_KEY_FILE).exists()
        files.text =
            listOf(
                getString(if (hasHosts) R.string.seal_ssh_known_hosts_ok else R.string.seal_ssh_known_hosts_missing),
                getString(if (hasKey) R.string.seal_ssh_key_ok else R.string.seal_ssh_key_missing),
            ).joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateStatus)
    }

    override fun onPause() {
        handler.removeCallbacks(updateStatus)
        super.onPause()
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun text(
        value: String,
        size: Float,
        bold: Boolean = false,
    ) = TextView(this).apply {
        text = value
        textSize = size
        setLineSpacing(0f, 1.25f)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun section(title: String) =
        text(title, 18f, bold = true).apply {
            setPadding(0, dp(24), 0, dp(8))
            isFocusable = true
            ViewCompat.setAccessibilityHeading(this, true)
        }

    private fun button(
        label: Int,
        outlined: Boolean,
        action: () -> Unit,
    ): MaterialButton {
        val style =
            if (outlined) {
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            } else {
                com.google.android.material.R.attr.materialButtonStyle
            }
        return MaterialButton(this, null, style).apply {
            text = getString(label)
            minHeight = dp(48)
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        dp(8)
                }
            setOnClickListener { action() }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** Rejects obviously wrong files (e.g. a private key picked by mistake) before they are trusted. */
        internal fun looksLikeKnownHosts(content: String): Boolean {
            if ("PRIVATE KEY" in content) return false
            return content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.any { line ->
                val parts = line.split(Regex("\\s+"))
                val keyIndex = if (parts.firstOrNull()?.startsWith("@") == true) 2 else 1
                parts.size > keyIndex + 1 && (parts[keyIndex].startsWith("ssh-") || parts[keyIndex].startsWith("ecdsa-"))
            }
        }
    }
}
