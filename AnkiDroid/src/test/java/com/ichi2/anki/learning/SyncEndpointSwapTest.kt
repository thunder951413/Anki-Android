// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.learning

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.common.preferences.sharedPrefs
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SyncEndpointSwapTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.sharedPrefs()

    private fun key(id: Int) = context.getString(id)

    @Before
    fun loggedInToAnkiWeb() {
        context.getSharedPreferences(SyncEndpointSwap.STORE, Context.MODE_PRIVATE).edit { clear() }
        prefs.edit {
            clear()
            putString(key(R.string.hkey_key), "ankiweb-key")
            putString(key(R.string.username_key), "child@example.com")
        }
    }

    @Test
    fun `disconnecting restores the previous sync server and login`() {
        SyncEndpointSwap.apply(context, "http://127.0.0.1:18765/", "u@h:22>8080")
        assertEquals("http://127.0.0.1:18765/", prefs.getString(key(R.string.custom_sync_server_collection_url_key), null))
        assertTrue(prefs.getBoolean(key(R.string.custom_sync_server_enabled_key), false))
        assertNull(prefs.getString(key(R.string.hkey_key), null), "AnkiWeb key must not be sent to the tunnel server")

        SyncEndpointSwap.restore(context)
        assertNull(prefs.getString(key(R.string.custom_sync_server_collection_url_key), null))
        assertFalse(prefs.getBoolean(key(R.string.custom_sync_server_enabled_key), true))
        assertEquals("ankiweb-key", prefs.getString(key(R.string.hkey_key), null))
        assertEquals("child@example.com", prefs.getString(key(R.string.username_key), null))
        assertFalse(SyncEndpointSwap.isActive(context))
    }

    @Test
    fun `the tunnel login is remembered for the next connection`() {
        SyncEndpointSwap.apply(context, "http://127.0.0.1:18765/", "u@h:22>8080")
        prefs.edit { putString(key(R.string.hkey_key), "tunnel-key") }
        SyncEndpointSwap.restore(context)

        SyncEndpointSwap.apply(context, "http://127.0.0.1:40001/", "u@h:22>8080")
        assertEquals("tunnel-key", prefs.getString(key(R.string.hkey_key), null))
        assertEquals("http://127.0.0.1:40001/", prefs.getString(key(R.string.custom_sync_server_collection_url_key), null))
        SyncEndpointSwap.restore(context)
        assertEquals("ankiweb-key", prefs.getString(key(R.string.hkey_key), null))
    }

    @Test
    fun `applying twice keeps the original backup`() {
        SyncEndpointSwap.apply(context, "http://127.0.0.1:18765/", "p")
        SyncEndpointSwap.apply(context, "http://127.0.0.1:18766/", "p")
        SyncEndpointSwap.restore(context)
        assertEquals("ankiweb-key", prefs.getString(key(R.string.hkey_key), null))
        assertNull(prefs.getString(key(R.string.custom_sync_server_collection_url_key), null))
    }
}
