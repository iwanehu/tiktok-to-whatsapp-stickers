package com.thenicebott.tiktokstickers

import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream

object SourcePolicy {
    private val hosts = listOf("tiktok.com", "tiktokcdn.com", "tiktokcdn-us.com", "byteoversea.com", "ibytedtos.com", "muscdn.com", "byteimg.com")
    fun allowed(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            hosts.any { uri.host?.lowercase() == it || uri.host?.lowercase()?.endsWith(".$it") == true }
    }.getOrDefault(false)
    fun download(value: String): ByteArray {
        var current = value
        repeat(6) {
            require(allowed(current)) { "Origen del sticker no permitido." }
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000; connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                val code = connection.responseCode
                if (code in 300..399) {
                    current = URL(URL(current), connection.getHeaderField("Location") ?: error("Redirección inválida")).toString()
                } else {
                    require(code == 200) { "No se pudo descargar el original ($code)." }
                    require(connection.contentLengthLong <= 10L * 1024 * 1024) { "Sticker demasiado grande." }
                    return connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            require(output.size() + n <= 10 * 1024 * 1024) { "Sticker demasiado grande." }
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    }
                }
            } finally { connection.disconnect() }
        }
        error("Demasiadas redirecciones.")
    }
}
