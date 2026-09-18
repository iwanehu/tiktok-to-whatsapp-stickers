package com.thenicebott.tiktokstickers

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import android.util.Base64

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pendingImport: String? = null
    private var pendingExport: String? = null
    private var exportingPack: StickerPack? = null
    private var processing = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val chooseFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { files -> fileCallback?.onReceiveValue(files.toTypedArray()); fileCallback = null }
    private val importer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingImport ?: return@registerForActivityResult
        pendingImport = null
        if (result.resultCode != RESULT_OK) reply(id, error = "Importación cancelada.")
        else {
            val file = File(cacheDir, "import-result.json")
            try { reply(id, JSONObject(file.readText())) } catch (e: Exception) { reply(id, error = "No se pudo recuperar la importación.") }
            finally { file.delete() }
        }
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingExport ?: return@registerForActivityResult
        val pack = exportingPack
        val confirmed = pack != null && (isWhitelisted(pack.identifier) || result.resultCode == RESULT_OK)
        reply(id, JSONObject().put("status", if (confirmed) "confirmed" else "cancelled"))
        pendingExport = null; exportingPack = null
    }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StickerPackRepository.loadPacks(this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false; web.settings.allowContentAccess = false
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.host != "appassets.androidplatform.net"
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null); fileCallback = callback; chooseFiles.launch("image/*"); return true
            }
        }
        web.addJavascriptInterface(LocalBridge(), "StickerNative")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }
    private inner class LocalBridge {
        @JavascriptInterface fun postMessage(raw: String) {
            if (raw.length > 22 * 1024 * 1024) return
            val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
            val id = message.optString("id"); if (id.length !in 1..64) return
            runOnUiThread {
                if (pendingImport != null || pendingExport != null || processing) { reply(id, error = "Termina la operación anterior."); return@runOnUiThread }
                when (message.optString("method")) {
                    "importTikTok" -> { pendingImport = id; importer.launch(Intent(this@MainActivity, TikTokBridge::class.java)) }
                    "exportPack" -> export(id, message.optJSONObject("payload") ?: JSONObject())
                    else -> reply(id, error = "Operación desconocida.")
                }
            }
        }
    }
    private fun reply(id: String, result: JSONObject? = null, error: String? = null) {
        val response = JSONObject().put("id", id)
        if (error != null) response.put("error", error) else response.put("result", result)
        web.evaluateJavascript("window.__stickerReply && window.__stickerReply($response)", null)
    }
    private fun export(id: String, payload: JSONObject) {
        processing = true
        lifecycleScope.launch {
            try {
                val pack = withContext(Dispatchers.IO) { buildPack(payload) }
                StickerPackRepository.setPack(this@MainActivity, pack)
                val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK")
                    .putExtra("sticker_pack_id", pack.identifier)
                    .putExtra("sticker_pack_authority", "$packageName.stickercontentprovider")
                    .putExtra("sticker_pack_name", pack.name)
                val target = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { intent.setPackage(it).resolveActivity(packageManager) != null }
                    ?: error("Instala WhatsApp o WhatsApp Business para añadir el paquete.")
                intent.setPackage(target); pendingExport = id; exportingPack = pack
                exporter.launch(intent)
            } catch (e: Exception) { pendingExport = null; exportingPack = null; reply(id, error = e.message ?: "No se pudo exportar.") }
            finally { processing = false }
        }
    }
    private suspend fun buildPack(payload: JSONObject): StickerPack {
        val name = payload.getString("name").trim(); val author = payload.getString("author").trim()
        require(name.length in 1..128 && author.length in 1..128) { "Nombre y autor obligatorios (máximo 128 caracteres)." }
        val entries = payload.getJSONArray("stickers"); require(entries.length() in 3..30) { "Selecciona entre 3 y 30 stickers." }
        val identifier = "sb_${UUID.randomUUID()}"
        val dir = StickerPackRepository.getStickerPackDir(this, identifier)
        val hashes = mutableSetOf<String>(); var type: Boolean? = null
        try {
            val stickers = (0 until entries.length()).map { i ->
                val input = entries.getJSONObject(i)
                val encoded = input.getString("base64"); require(encoded.length <= 700000) { "Sticker demasiado grande." }
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(hashes.add(hash)) { "Hay stickers duplicados." }
                val file = File(dir, "sticker_$i.webp")
                val result = StickerProcessor.processStickerBytes(this, bytes, file)
                require(result.isAnimated == input.getBoolean("animated")) { "La animación no coincide con el original." }
                require(type == null || type == result.isAnimated) { "Separa los animados de los estáticos." }
                type = result.isAnimated
                StickerInPack(file.name)
            }
            TrayIconGenerator.generateFromWebp(File(dir, stickers.first().imageFileName), File(dir, "tray.png"))
            return StickerPack(identifier, name, author, "tray.png", stickers, type == true)
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }
    private fun isWhitelisted(identifier: String): Boolean = listOf("com.whatsapp", "com.whatsapp.w4b").any { pkg ->
        runCatching {
            val uri = Uri.parse("content://$pkg.provider.sticker_whitelist_check/is_whitelisted").buildUpon()
                .appendQueryParameter("authority", "$packageName.stickercontentprovider").appendQueryParameter("identifier", identifier).build()
            contentResolver.query(uri, null, null, null, null)?.use { it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow("result")) == 1 } == true
        }.getOrDefault(false)
    }
    override fun onDestroy() { fileCallback?.onReceiveValue(null); web.removeJavascriptInterface("StickerNative"); web.destroy(); super.onDestroy() }
}
