// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.learning

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.mediaFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/** Trusted bundled UI. Card text is displayed as text, never executed as HTML. */
class LearningActivity : AnkiActivity() {
    private lateinit var web: WebView
    @Volatile private var collectionMedia: File? = null
    private var speechRequest: Int? = null
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        speechRequest?.let { id ->
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            reply(id, text, if (result.resultCode == Activity.RESULT_OK && text != null) null else "没有识别到语音，可以重试或输入答案。")
        }
        speechRequest = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { view, insets ->
            val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        web.webChromeClient = WebChromeClient()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.addJavascriptInterface(Bridge(), "LearningBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                val denied = { WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf())) }
                if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || request.method != "GET") return denied()
                val path = uri.path.orEmpty()
                if (".." in path || "\\" in path) return denied()
                return try {
                    if (path.startsWith("/assets/learning/")) {
                        val relative = path.removePrefix("/assets/")
                        val mime = when (relative.substringAfterLast('.')) {
                            "mjs", "js" -> "text/javascript"
                            "css" -> "text/css"
                            "html" -> "text/html"
                            "json" -> "application/json"
                            "png" -> "image/png"
                            else -> "application/octet-stream"
                        }
                        WebResourceResponse(mime, "utf-8", assets.open(relative))
                    } else if (path.startsWith("/media/")) {
                        val name = uri.lastPathSegment ?: return denied()
                        if ('/' in name || '\\' in name || ".." in name) return denied()
                        val folder = collectionMedia ?: return denied()
                        val file = File(folder, name).canonicalFile
                        if (file.parentFile != folder.canonicalFile) return denied()
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                        WebResourceResponse(mime, null, file.inputStream())
                    } else denied()
                } catch (_: Exception) { denied() }
            }
        }
        lifecycleScope.launch {
            collectionMedia = withCol { mediaFolder }
            web.loadUrl("https://appassets.androidplatform.net/assets/learning/index.html")
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            if (raw.length > 100_000) return
            runOnUiThread {
                lifecycleScope.launch {
                    var id = 0
                    try {
                        val input = JSONObject(raw)
                        id = input.getInt("id")
                        val params = input.optJSONObject("params") ?: JSONObject()
                        val result: Any? = when (input.getString("method")) {
                            "courses" -> withCol { LearningRepository(this).courses() }
                            "lesson" -> withCol { LearningRepository(this).lesson(params.getString("deckId").toLong()) }
                            "answer" -> withCol { LearningRepository(this).answer(params) }
                            "settings" -> withCol { JSONObject().put("fsrs", config.get<Boolean>("fsrs", false) == true) }
                            "reviewSettings" -> {
                                val deckId = withCol { decks.selected() }
                                startActivity(com.ichi2.anki.pages.DeckOptions.getIntent(this@LearningActivity, deckId))
                                true
                            }
                            "sshSettings" -> { startActivity(Intent(this@LearningActivity, SshSettingsActivity::class.java)); true }
                            "speech" -> {
                                check(speechRequest == null) { "已有语音识别正在进行。" }
                                speechRequest = id
                                try {
                                    speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, params.optString("locale", "en-US"))
                                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                                    })
                                } catch (error: Exception) { speechRequest = null; throw error }
                                return@launch
                            }
                            else -> error("未知操作")
                        }
                        reply(id, result)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { reply(id, null, error.localizedMessage ?: "操作失败，请重试。") }
                }
            }
        }
    }

    private fun reply(id: Int, result: Any?, error: String? = null) {
        if (isDestroyed) return
        val message = JSONObject().put("id", id).put("result", result ?: JSONObject.NULL)
        if (error != null) message.put("error", error)
        web.evaluateJavascript("globalThis.learningReply($message)", null)
    }

    override fun onDestroy() {
        web.removeJavascriptInterface("LearningBridge")
        web.destroy()
        super.onDestroy()
    }
}
