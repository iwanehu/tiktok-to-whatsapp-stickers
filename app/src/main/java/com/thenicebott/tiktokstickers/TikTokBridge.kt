package com.thenicebott.tiktokstickers

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File
import android.util.Base64
import kotlin.coroutines.resume

/** No JavaScript interface is exposed to TikTok. No password fields or cookies are read. */
class TikTokBridge : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var scan: Button
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "Inicia sesión en TikTok y abre Mensajes → Stickers → Favoritos."; setPadding(20,20,20,20) }
        val cancel = Button(this).apply { text = "Volver sin importar"; setOnClickListener { finish() } }
        scan = Button(this).apply { text = "Detectar favoritos"; setOnClickListener { detect() } }
        web = WebView(this)
        web.settings.javaScriptEnabled = true; web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false; web.settings.allowContentAccess = false
        web.settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return true
                return request.url.scheme != "https" || !(host == "tiktok.com" || host.endsWith(".tiktok.com"))
            }
        }
        root.addView(status); root.addView(cancel); root.addView(scan); root.addView(web, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root); web.loadUrl("https://www.tiktok.com/messages")
    }
    private suspend fun evaluate(js: String): String = suspendCancellableCoroutine { continuation ->
        web.evaluateJavascript(js) { if (continuation.isActive) continuation.resume(it ?: "null") }
    }
    private fun detect() {
        scan.isEnabled = false
        lifecycleScope.launch {
            try {
                require(web.url?.let { android.net.Uri.parse(it).host?.let { h -> h == "tiktok.com" || h.endsWith(".tiktok.com") } } == true)
                status.text = "Recorriendo el panel abierto…"
                evaluate(assets.open("detect-favorites.js").bufferedReader().use { it.readText() })
                val result = withTimeout(45000) {
                    var value: JSONObject? = null
                    while (value == null) {
                        delay(300)
                        val raw = evaluate("JSON.stringify(window.__sbScanResult || null)")
                        val decoded = JSONTokener(raw).nextValue()
                        if (decoded is String && decoded != "null") value = JSONObject(decoded)
                    }
                    value
                }
                if (result.has("error")) error(result.getString("error"))
                val urls = result.getJSONArray("urls"); val output = JSONArray(); val hashes = mutableSetOf<String>(); var failed = 0; var totalEncoded = 0
                for (i in 0 until minOf(120, urls.length())) {
                    status.text = "Preparando ${i+1}/${urls.length()}…"
                    try {
                        val sticker = withContext(Dispatchers.IO) {
                            val bytes = SourcePolicy.download(urls.getString(i))
                            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                            if (!hashes.add(hash)) return@withContext null
                            val temp = File.createTempFile("import_", ".webp", cacheDir)
                            try {
                                val processed = StickerProcessor.processStickerBytes(this@TikTokBridge, bytes, temp)
                                JSONObject().put("name", "Sticker ${i+1}").put("animated", processed.isAnimated)
                                    .put("base64", Base64.encodeToString(temp.readBytes(), Base64.NO_WRAP))
                            } finally { temp.delete() }
                        }
                        if (sticker != null) {
                            val size = sticker.getString("base64").length
                            if (totalEncoded + size > 12 * 1024 * 1024) { failed += urls.length() - i; break }
                            totalEncoded += size; output.put(sticker)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failed++ }
                }
                require(output.length() > 0) { "No se pudieron importar originales compatibles. TikTok puede no exponerlos en este panel." }
                val response = JSONObject().put("stickers", output).put("warning", "$failed archivos no compatibles. ${result.optString("warning")}")
                withContext(Dispatchers.IO) { File(cacheDir,"import-result.json").writeText(response.toString()) }
                setResult(RESULT_OK); finish()
            } catch (e: Exception) { status.text = e.message ?: "No se pudo detectar." }
            finally { scan.isEnabled = true }
        }
    }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
