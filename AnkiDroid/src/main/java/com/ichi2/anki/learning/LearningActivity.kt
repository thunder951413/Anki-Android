// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.learning

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.libanki.mediaFolder
import com.ichi2.themes.Themes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

/**
 * Hosts the bundled interactive-lesson UI (assets/learning) in a WebView.
 *
 * Trusted bundled UI only: navigation is blocked, the only reachable content is our assets and
 * individual files of collection.media, and card text is rendered as text, never as HTML.
 */
class LearningActivity : AnkiActivity() {
    private lateinit var web: WebView

    @Volatile private var collectionMedia: File? = null
    private var speechRequest: Int? = null

    private val speech =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            speechRequest?.let { id ->
                val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (result.resultCode == Activity.RESULT_OK && text != null) {
                    reply(id, text)
                } else {
                    reply(id, null, getString(R.string.seal_learning_speech_none))
                }
            }
            speechRequest = null
        }

    /** Lets the page close dialogs / leave a lesson before the activity is finished. */
    private val backCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("globalThis.learningBack ? globalThis.learningBack() : false") { handled ->
                    if (handled != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        // A tunnel left over from a killed process must not keep pointing sync at a dead port.
        SyncEndpointSwap.restoreIfOrphaned(this)
        web = WebView(this)
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { view, insets ->
            val padding =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
                )
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        web.webChromeClient = WebChromeClient()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // Follow the system font size, as AnkiDroid's reviewer does.
            textZoom = (resources.configuration.fontScale * 100).toInt()
            mediaPlaybackRequiresUserGesture = true
        }
        web.addJavascriptInterface(Bridge(), "LearningBridge")
        web.webViewClient = AssetClient()
        onBackPressedDispatcher.addCallback(this, backCallback)
        lifecycleScope.launch {
            collectionMedia = withCol { mediaFolder }
            web.loadUrl(pageUrl())
        }
    }

    private fun pageUrl(): String {
        val theme = if (Themes.isNightTheme) "dark" else "light"
        val language = Locale.getDefault().toLanguageTag()
        return "https://appassets.androidplatform.net/assets/learning/index.html"
            .toUri()
            .buildUpon()
            .appendQueryParameter("theme", theme)
            .appendQueryParameter("lang", language)
            .build()
            .toString()
    }

    private inner class AssetClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = true

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse {
            val uri = request.url
            if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || request.method != "GET") return denied()
            val path = uri.path.orEmpty()
            if (".." in path || "\\" in path) return denied()
            return try {
                when {
                    path.startsWith("/assets/learning/") -> {
                        val relative = path.removePrefix("/assets/")
                        WebResourceResponse(mimeType(relative), "utf-8", assets.open(relative))
                    }
                    path.startsWith("/media/") -> {
                        val name = uri.lastPathSegment ?: return denied()
                        if ('/' in name || '\\' in name || ".." in name) return denied()
                        val folder = collectionMedia ?: return denied()
                        val file = File(folder, name).canonicalFile
                        if (file.parentFile != folder.canonicalFile || !file.isFile) return notFound()
                        WebResourceResponse(mimeType(file.name), null, file.inputStream())
                    }
                    else -> denied()
                }
            } catch (_: Exception) {
                notFound()
            }
        }

        private fun mimeType(name: String): String =
            when (val extension = name.substringAfterLast('.', "").lowercase()) {
                "mjs", "js" -> "text/javascript"
                "css" -> "text/css"
                "html" -> "text/html"
                "json" -> "application/json"
                "webp" -> "image/webp"
                "png" -> "image/png"
                "svg" -> "image/svg+xml"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
            }

        private fun denied() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))

        private fun notFound() =
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
    }

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            if (raw.length > 100_000) return
            runOnUiThread { lifecycleScope.launch { handle(raw) } }
        }
    }

    private suspend fun handle(raw: String) {
        var id = 0
        try {
            val input = JSONObject(raw)
            id = input.getInt("id")
            val params = input.optJSONObject("params") ?: JSONObject()
            val result: Any? =
                when (input.getString("method")) {
                    "courses" ->
                        withCol {
                            JSONObject()
                                .put("courses", LearningRepository(this).courses())
                                .put("fsrs", config.get<Boolean>("fsrs", false) == true)
                                .put("sshConnected", SshTunnelService.status.state == SshTunnelService.State.CONNECTED)
                        }
                    "lesson" -> withCol { LearningRepository(this).lesson(params.getString("deckId").toLong()) }
                    "answer" -> withCol { LearningRepository(this).answer(params) }
                    "reviewSettings" -> {
                        val deckId = params.optString("deckId").toLongOrNull() ?: withCol { decks.selected() }
                        startActivity(
                            com.ichi2.anki.pages.DeckOptions
                                .getIntent(this, deckId),
                        )
                        true
                    }
                    "sshSettings" -> {
                        startActivity(Intent(this, SshSettingsActivity::class.java))
                        true
                    }
                    "close" -> {
                        finish()
                        true
                    }
                    "speech" -> {
                        startSpeech(id, params.optString("locale", "en-US"))
                        return
                    }
                    else -> throw LearningException(R.string.seal_learning_error_unknown_action)
                }
            reply(id, result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LearningException) {
            val message = getString(error.messageRes)
            reply(id, null, if (error.stale) "STALE: $message" else message)
        } catch (error: Exception) {
            Timber.w(error, "learning bridge")
            reply(id, null, error.localizedMessage ?: getString(R.string.seal_learning_error_generic))
        }
    }

    private fun startSpeech(
        id: Int,
        locale: String,
    ) {
        if (speechRequest != null) throw LearningException(R.string.seal_learning_speech_busy)
        speechRequest = id
        try {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                },
            )
        } catch (error: ActivityNotFoundException) {
            Timber.w(error, "learning: no speech recognizer")
            speechRequest = null
            throw LearningException(R.string.seal_learning_speech_unavailable)
        } catch (error: Exception) {
            speechRequest = null
            throw error
        }
    }

    private fun reply(
        id: Int,
        result: Any?,
        error: String? = null,
    ) {
        if (isDestroyed || !::web.isInitialized) return
        val message = JSONObject().put("id", id).put("result", result ?: JSONObject.NULL)
        if (error != null) message.put("error", error)
        web.evaluateJavascript("globalThis.learningReply($message)", null)
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.removeJavascriptInterface("LearningBridge")
            web.destroy()
        }
        super.onDestroy()
    }
}
