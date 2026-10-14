// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.learning

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.R
import com.ichi2.anki.common.preferences.sharedPrefs

/**
 * Points AnkiDroid's sync at the SSH tunnel while it is up, and puts everything back afterwards.
 *
 * Two logins are kept apart:
 * - the user's own settings from before the tunnel (AnkiWeb or another custom server), restored on
 *   disconnect, and
 * - the login made through the tunnel, remembered per tunnel profile so reconnecting does not ask
 *   the child to log in again.
 */
internal object SyncEndpointSwap {
    internal const val STORE = "learning_ssh_sync_backup"
    private const val ACTIVE = "active"
    private const val PROFILE = "profile"
    private const val LOOPBACK_PREFIX = "http://127.0.0.1:"

    private class Keys(
        context: Context,
    ) {
        val uri = context.getString(R.string.custom_sync_server_collection_url_key)
        val enabled = context.getString(R.string.custom_sync_server_enabled_key)
        val hkey = context.getString(R.string.hkey_key)
        val username = context.getString(R.string.username_key)
        val currentUri = context.getString(R.string.current_sync_uri_key)
        val strings get() = listOf(uri, hkey, username, currentUri)
    }

    private fun backup(context: Context): SharedPreferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun isActive(context: Context) = backup(context).getBoolean(ACTIVE, false)

    /** Switches sync to [endpoint]. Safe to call again while already active (e.g. on reconnect). */
    fun apply(
        context: Context,
        endpoint: String,
        profile: String,
    ) {
        val prefs = context.sharedPrefs()
        val keys = Keys(context)
        val backup = backup(context)
        if (!backup.getBoolean(ACTIVE, false)) {
            val previousUri = prefs.getString(keys.uri, null)
            // Older builds overwrote the user's settings with a loopback URL and never restored them.
            // That login belongs to the tunnel; the original settings are unknown, so restore to "no custom server".
            val legacyTunnel = previousUri?.startsWith(LOOPBACK_PREFIX) == true
            backup.edit {
                putBoolean(ACTIVE, true)
                putString(PROFILE, profile)
                for (key in keys.strings) {
                    val value = if (legacyTunnel) null else prefs.getString(key, null)
                    if (value == null) remove("orig.$key") else putString("orig.$key", value)
                }
                if (legacyTunnel) {
                    putBoolean("orig.${keys.enabled}", false)
                    if (backup.getString("tunnel.$profile.${keys.hkey}", null) == null) {
                        prefs.getString(keys.hkey, null)?.let { putString("tunnel.$profile.${keys.hkey}", it) }
                        prefs.getString(keys.username, null)?.let { putString("tunnel.$profile.${keys.username}", it) }
                    }
                } else {
                    putBoolean("orig.${keys.enabled}", prefs.getBoolean(keys.enabled, false))
                }
            }
        }
        val tunnelHkey = backup.getString("tunnel.$profile.${keys.hkey}", null)
        val tunnelUser = backup.getString("tunnel.$profile.${keys.username}", null)
        prefs.edit {
            putString(keys.uri, endpoint)
            putBoolean(keys.enabled, true)
            remove(keys.currentUri)
            if (tunnelHkey != null) putString(keys.hkey, tunnelHkey) else remove(keys.hkey)
            if (tunnelUser != null) putString(keys.username, tunnelUser) else remove(keys.username)
        }
    }

    /** Remembers the tunnel login and restores the user's previous sync settings. */
    fun restore(context: Context) {
        val backup = backup(context)
        if (!backup.getBoolean(ACTIVE, false)) return
        val prefs = context.sharedPrefs()
        val keys = Keys(context)
        val profile = backup.getString(PROFILE, "") ?: ""
        backup.edit {
            for (key in listOf(keys.hkey, keys.username)) {
                val value = prefs.getString(key, null)
                if (value == null) remove("tunnel.$profile.$key") else putString("tunnel.$profile.$key", value)
            }
        }
        prefs.edit {
            for (key in keys.strings) {
                val value = backup.getString("orig.$key", null)
                if (value == null) remove(key) else putString(key, value)
            }
            putBoolean(keys.enabled, backup.getBoolean("orig.${keys.enabled}", false))
        }
        backup.edit {
            for (key in keys.strings + keys.enabled) remove("orig.$key")
            remove(PROFILE)
            putBoolean(ACTIVE, false)
        }
    }

    /** After a process death the service is gone but the swap is still in place: undo it. */
    fun restoreIfOrphaned(context: Context) {
        if (!SshTunnelService.running && isActive(context)) restore(context)
    }
}
