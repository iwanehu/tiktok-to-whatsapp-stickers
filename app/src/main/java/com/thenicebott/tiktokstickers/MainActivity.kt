package com.thenicebott.tiktokstickers

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.ArrayDeque

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var stickerGrid: RecyclerView
    private lateinit var panelTitle: TextView
    private lateinit var btnScan: Button
    private lateinit var btnAddToWhatsApp: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnTogglePanel: ImageView
    private lateinit var adapter: StickerThumbAdapter

    private var detectedUrls: List<String> = emptyList()
    private val pendingPacks = ArrayDeque<StickerPack>()
    private var pendingPackTotal = 0
    private var launchedPackCount = 0
    private val accumulatedUrls = linkedSetOf<String>()
    private var isPanelExpanded = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        stickerGrid = findViewById(R.id.stickerGrid)
        panelTitle = findViewById(R.id.panelTitle)
        btnScan = findViewById(R.id.btnScan)
        btnAddToWhatsApp = findViewById(R.id.btnAddToWhatsApp)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        btnTogglePanel = findViewById(R.id.btnTogglePanel)

        adapter = StickerThumbAdapter { count ->
            if (count == 0) {
                btnAddToWhatsApp.text = "Añadir a WhatsApp"
                btnAddToWhatsApp.isEnabled = false
            } else {
                btnAddToWhatsApp.text = "Añadir $count a WhatsApp"
                btnAddToWhatsApp.isEnabled = true
            }
        }
        stickerGrid.layoutManager = GridLayoutManager(this, 4)
        stickerGrid.adapter = adapter

        StickerPackRepository.loadPacks(this)

        setupWebView()

        btnScan.setOnClickListener { scanForStickers() }
        btnAddToWhatsApp.setOnClickListener {
            if (pendingPacks.isNotEmpty()) {
                launchNextPendingPack()
            } else {
                processAndAddToWhatsApp()
            }
        }

        btnTogglePanel.setOnClickListener {
            togglePanel()
        }
        
        if (detectedUrls.isEmpty()) {
            isPanelExpanded = false
            updatePanelVisibility()
        }
    }

    private fun togglePanel() {
        isPanelExpanded = !isPanelExpanded
        updatePanelVisibility()
    }

    private fun updatePanelVisibility() {
        val visibility = if (isPanelExpanded) View.VISIBLE else View.GONE
        stickerGrid.visibility = visibility
        btnAddToWhatsApp.visibility = visibility
        statusText.visibility = visibility
        
        btnTogglePanel.rotation = if (isPanelExpanded) 0f else 180f
    }

    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        
        webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme
                
                if (scheme != null && scheme != "http" && scheme != "https") {
                    return true 
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
            }
        }

        webView.loadUrl("https://www.tiktok.com/messages")
    }

    private fun scanForStickers() {
        accumulatedUrls.clear()
        detectedUrls = emptyList()
        adapter.clear()
        btnScan.isEnabled = false
        btnAddToWhatsApp.isEnabled = false
        statusText.text = "Buscando y cargando todos los stickers visibles…"
        val script = assets.open("detect_stickers.js").bufferedReader().use { it.readText() }
        webView.evaluateJavascript(script) { }
    }

    private inner class AndroidBridge {
        @JavascriptInterface
        fun onStickersFound(urlsJson: String) {
            val array = JSONArray(urlsJson)
            for (i in 0 until array.length()) {
                val url = array.optString(i)
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    accumulatedUrls.add(url)
                }
            }
            runOnUiThread {
                detectedUrls = accumulatedUrls.toList()
                adapter.submitList(detectedUrls)
                panelTitle.text = "Stickers detectados: ${detectedUrls.size}"
                statusText.text = "${detectedUrls.size} sticker(s) únicos encontrados…"
                btnAddToWhatsApp.isEnabled = detectedUrls.isNotEmpty()
                
                if (detectedUrls.isNotEmpty() && !isPanelExpanded) {
                    togglePanel()
                }
            }
        }

        @JavascriptInterface
        fun onScanFinished(total: Int) {
            runOnUiThread {
                btnScan.isEnabled = true
                btnAddToWhatsApp.isEnabled = accumulatedUrls.isNotEmpty()
                statusText.text = if (accumulatedUrls.isEmpty()) {
                    "No se encontraron stickers. Abre tus stickers guardados y vuelve a escanear."
                } else {
                    "Escaneo terminado: ${accumulatedUrls.size} sticker(s) únicos."
                }
            }
        }

        @JavascriptInterface
        fun onScanError(message: String) {
            runOnUiThread {
                btnScan.isEnabled = true
                btnAddToWhatsApp.isEnabled = accumulatedUrls.isNotEmpty()
                statusText.text = "No se pudo completar el escaneo: $message"
            }
        }
    }

    private fun processAndAddToWhatsApp() {
        
        val urlsToProcess = detectedUrls.filter { adapter.selectedUrls.contains(it) }
        
        if (urlsToProcess.isEmpty()) return

        btnAddToWhatsApp.isEnabled = false
        statusText.text = "Convirtiendo stickers a formato WhatsApp…"
        progressBar.visibility = android.view.View.VISIBLE
        progressBar.max = urlsToProcess.size
        progressBar.progress = 0

        lifecycleScope.launch {
            try {
                android.util.Log.i("TikTokStickers", "Stickers a procesar: $urlsToProcess")
                val packs = withContext(Dispatchers.IO) {
                    buildStickerPacks(urlsToProcess) { current, total ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            progressBar.progress = current
                            statusText.text = "Procesando $current de $total…"
                        }
                    }
                }

                if (packs.isEmpty()) {
                    statusText.text = "Se necesitan al menos 3 stickers del mismo tipo (estático o animado) para crear un paquete válido para WhatsApp."
                    return@launch
                }

                packs.forEach { StickerPackRepository.setPack(this@MainActivity, it) }

                pendingPacks.clear()
                pendingPacks.addAll(packs)
                pendingPackTotal = packs.size
                launchedPackCount = 0
                launchNextPendingPack()
            } catch (e: Exception) {
                statusText.text = "Error al convertir: ${e.message}"
            } finally {
                btnAddToWhatsApp.isEnabled = true
                progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private suspend fun buildStickerPacks(
        urls: List<String>, 
        onProgressUpdate: (current: Int, total: Int) -> Unit
    ): List<StickerPack> {
        val staticFiles = mutableListOf<File>()
        val animatedFiles = mutableListOf<File>()
        var firstError: String? = null
        val batchDir = File(cacheDir, "sticker_batch_${System.currentTimeMillis()}").apply { mkdirs() }

        urls.forEachIndexed { index, url ->
            try {
                
                val tempOutput = File(batchDir, "sticker_$index.webp")
                val result = StickerProcessor.processStickerUrl(this, url, tempOutput)
                if (result.isAnimated) {
                    animatedFiles.add(result.file)
                } else {
                    staticFiles.add(result.file)
                }
            } catch (e: Exception) {
                
                android.util.Log.e("TikTokStickers", "Error procesando sticker en $url", e)
                if (firstError == null) firstError = e.message ?: e.toString()
            } finally {
                onProgressUpdate(index + 1, urls.size)
            }
        }
        
        if (staticFiles.isEmpty() && animatedFiles.isEmpty() && firstError != null) {
            batchDir.deleteRecursively()
            throw Exception(firstError)
        }

        val packs = mutableListOf<StickerPack>()
        val uniqueSuffix = System.currentTimeMillis().toString()
        packs += createPacksForType(staticFiles, false, uniqueSuffix, packs.size)
        packs += createPacksForType(animatedFiles, true, uniqueSuffix, packs.size)
        batchDir.deleteRecursively()
        return packs
    }

    private fun createPacksForType(
        files: List<File>,
        animated: Boolean,
        batchId: String,
        initialPackOffset: Int
    ): List<StickerPack> {
        if (files.size < MIN_STICKERS_PER_PACK) return emptyList()

        val validChunks = StickerPackChunker.chunk(files, MIN_STICKERS_PER_PACK, MAX_STICKERS_PER_PACK)
        val existingCount = StickerPackRepository.getAllPacks().size
        return validChunks.mapIndexed { chunkIndex, chunk ->
            val type = if (animated) "animated" else "static"
            val identifier = "tiktok_${type}_${batchId}_${chunkIndex + 1}"
            val packDir = StickerPackRepository.getStickerPackDir(this, identifier)
            val entries = chunk.mapIndexed { stickerIndex, source ->
                val name = "sticker_$stickerIndex.webp"
                source.copyTo(File(packDir, name), overwrite = true)
                StickerInPack(imageFileName = name)
            }
            val trayFile = File(packDir, "tray_icon.png")
            TrayIconGenerator.generateFromWebp(File(packDir, entries.first().imageFileName), trayFile)
            val number = existingCount + initialPackOffset + chunkIndex + 1
            val animatedLabel = if (animated) " animados" else ""
            StickerPack(
                identifier = identifier,
                name = "${getString(R.string.sticker_pack_name)}$animatedLabel #$number",
                publisher = getString(R.string.sticker_pack_publisher),
                trayImageFile = trayFile.name,
                stickers = entries,
                animatedStickerPack = animated
            )
        }
    }

    private fun launchNextPendingPack() {
        val pack = pendingPacks.pollFirst() ?: return
        launchedPackCount += 1
        statusText.text = "Abriendo paquete $launchedPackCount de $pendingPackTotal en WhatsApp…"
        btnAddToWhatsApp.text = if (pendingPacks.isEmpty()) {
            "Añadir a WhatsApp"
        } else {
            "Añadir siguiente paquete (${pendingPacks.size} pendiente(s))"
        }
        WhatsAppStickerLauncher.addPackToWhatsApp(this, pack)
    }

    companion object {
        
        private const val MIN_STICKERS_PER_PACK = 3
        private const val MAX_STICKERS_PER_PACK = 30
    }
}
