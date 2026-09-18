package com.thenicebott.tiktokstickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.aureusapps.android.webpandroid.decoder.WebPDecoder
import com.aureusapps.android.webpandroid.decoder.WebPDecodeListener
import com.aureusapps.android.webpandroid.decoder.WebPInfo
import com.aureusapps.android.webpandroid.encoder.WebPAnimEncoder
import com.aureusapps.android.webpandroid.encoder.WebPAnimEncoderOptions
import com.aureusapps.android.webpandroid.encoder.WebPConfig
import com.aureusapps.android.webpandroid.encoder.WebPEncoder
import com.aureusapps.android.webpandroid.encoder.WebPMuxAnimParams
import com.aureusapps.android.webpandroid.encoder.WebPPreset
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object StickerProcessor {

    private const val TARGET_SIZE = 512
    private const val MAX_STATIC_BYTES = 100 * 1024
    private const val MAX_ANIMATED_BYTES = 500 * 1024
    private const val MAX_ANIMATED_DURATION_MS = 10_000L

    data class ProcessResult(val file: File, val isAnimated: Boolean)

    suspend fun processStickerUrl(context: Context, url: String, outputFile: File): ProcessResult {
        val rawBytes = downloadBytes(url)
        return processStickerBytes(context, rawBytes, outputFile)
    }

    private fun downloadBytes(url: String): ByteArray = SourcePolicy.download(url)

    suspend fun processStickerBytes(context: Context, rawBytes: ByteArray, outputFile: File): ProcessResult {
        require(rawBytes.size in 1..10 * 1024 * 1024) { "Archivo vacío o demasiado grande." }
        val webp = rawBytes.size >= 12 && String(rawBytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(rawBytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        if (!webp) {
            val png = rawBytes.size >= 8 && rawBytes[0] == 0x89.toByte() && String(rawBytes, 1, 3, Charsets.US_ASCII) == "PNG"
            val jpeg = rawBytes.size >= 2 && rawBytes[0] == 0xff.toByte() && rawBytes[1] == 0xd8.toByte()
            require(png || jpeg) { "Formato no compatible; no se convertirá una animación en imagen fija." }
            require(!png || !String(rawBytes, Charsets.ISO_8859_1).contains("acTL")) { "APNG no compatible." }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16000000) { "Dimensiones no compatibles." }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: error("Imagen inválida")
            return try { ProcessResult(encodeStatic(context, bitmap, outputFile), false) } finally { bitmap.recycle() }
        }
        val temp = File.createTempFile("original_", ".webp", context.cacheDir)
        var frames: List<DecodedFrame> = emptyList()
        try {
            temp.writeBytes(rawBytes)
            frames = decodeAllFrames(context, temp)
            val animated = frames.size > 1
            if (animated) encodeAnimated(context, frames, outputFile) else encodeStatic(context, frames.first().bitmap, outputFile)
            require(outputFile.length() in 1..(if (animated) MAX_ANIMATED_BYTES else MAX_STATIC_BYTES).toLong()) { "Tamaño final inválido." }
            return ProcessResult(outputFile, animated)
        } finally { frames.forEach { it.bitmap.recycle() }; temp.delete() }
    }

    private data class DecodedFrame(val bitmap: Bitmap, val timestampMs: Long)

    private suspend fun decodeAllFrames(context: Context, file: File): List<DecodedFrame> {
        val frames = mutableListOf<DecodedFrame>()
        val decoder = WebPDecoder(context)

        try {
            decoder.setDataSource(Uri.fromFile(file))
            val info = decoder.decodeInfo()
            android.util.Log.i("TikTokStickers", "WebPInfo frameCount: ${info.frameCount}, hasAnimation: ${info.hasAnimation}")
            
            require(info.frameCount in 1..300) { "Demasiados fotogramas." }
            var frameIndex = 0
            var pixelBudget = 0L
            while (decoder.hasNextFrame()) {
                val frameResult = decoder.decodeNextFrame()
                val bitmap = frameResult.frame
                if (bitmap != null) {
                    pixelBudget += bitmap.width.toLong() * bitmap.height
                    require(pixelBudget <= 24000000) { "Animación demasiado grande para procesar con seguridad." }
                    frames.add(DecodedFrame(
                        bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false), 
                        frameResult.timestamp.toLong() 
                    ))
                    frameIndex++
                }
            }
            
            android.util.Log.i("TikTokStickers", "Decoded ${frames.size} frames")
            
            decoder.release()
            if (frames.isEmpty()) {
                throw IllegalStateException("No se pudo decodificar ningún frame del sticker")
            }
            return frames
        } catch (e: Exception) {
            decoder.release()
            frames.forEach { it.bitmap.recycle() }
            throw e
        }
    }

    private fun resizeToStickerCanvas(source: Bitmap): Bitmap {
        val canvas = Bitmap.createBitmap(TARGET_SIZE, TARGET_SIZE, Bitmap.Config.ARGB_8888)
        val androidCanvas = android.graphics.Canvas(canvas)
        androidCanvas.drawColor(Color.TRANSPARENT)

        val scale = minOf(
            TARGET_SIZE.toFloat() / source.width,
            TARGET_SIZE.toFloat() / source.height
        )
        val scaledWidth = (source.width * scale).toInt()
        val scaledHeight = (source.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

        val left = (TARGET_SIZE - scaledWidth) / 2f
        val top = (TARGET_SIZE - scaledHeight) / 2f
        androidCanvas.drawBitmap(scaled, left, top, null)

        if (scaled !== source) scaled.recycle()
        return canvas
    }

    private suspend fun encodeStatic(context: Context, sourceBitmap: Bitmap, outputFile: File): File {
        val resized = resizeToStickerCanvas(sourceBitmap)
        val candidate = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_tmp.webp")
        try {
            for (quality in listOf(80f, 65f, 50f, 35f, 20f)) {
                encodeStaticAtQuality(context, resized, candidate, quality)
                if (candidate.length() in 1..MAX_STATIC_BYTES.toLong()) {
                    candidate.copyTo(outputFile, overwrite = true)
                    return outputFile
                }
            }
            throw IllegalArgumentException("No se pudo reducir el sticker a 100 KB.")
        } finally { candidate.delete(); resized.recycle() }
    }

    private suspend fun encodeStaticAtQuality(context: Context, bitmap: Bitmap, outputFile: File, quality: Float) =
        suspendCancellableCoroutine<Unit> { cont ->
            val encoder = WebPEncoder(context, bitmap.width, bitmap.height)
            encoder.configure(
                config = WebPConfig(
                    lossless = WebPConfig.COMPRESSION_LOSSY,
                    quality = quality
                ),
                preset = WebPPreset.WEBP_PRESET_PICTURE
            )
            try {
                encoder.encode(bitmap, Uri.fromFile(outputFile))
                encoder.release()
                cont.resume(Unit)
            } catch (e: Exception) {
                encoder.release()
                cont.resumeWithException(e)
            }
        }

    private suspend fun encodeAnimated(context: Context, frames: List<DecodedFrame>, outputFile: File): File {
        require(frames.last().timestampMs <= MAX_ANIMATED_DURATION_MS) { "La animación supera 10 segundos; no se recorta automáticamente." }
        var previous = 0L
        frames.forEach { require(it.timestampMs - previous >= 8) { "Fotograma de duración inferior a 8 ms." }; previous = it.timestampMs }
        val resizedFrames = frames.map { it.copy(bitmap = resizeToStickerCanvas(it.bitmap)) }

        val candidate = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_tmp.webp")
        try {
            for (quality in listOf(75f, 50f, 30f, 15f, 5f)) {
                encodeAnimatedAtQuality(context, resizedFrames, candidate, quality)
                if (candidate.length() in 1..MAX_ANIMATED_BYTES.toLong()) {
                    candidate.copyTo(outputFile, overwrite = true)
                    return outputFile
                }
            }
            throw IllegalArgumentException("El sticker animado supera 500 KB incluso con máxima compresión.")
        } finally { candidate.delete(); resizedFrames.forEach { it.bitmap.recycle() } }
    }

    private suspend fun encodeAnimatedAtQuality(
        context: Context,
        frames: List<DecodedFrame>,
        outputFile: File,
        quality: Float
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val first = frames.first()
        val encoder = WebPAnimEncoder(
            context = context,
            width = first.bitmap.width,
            height = first.bitmap.height,
            options = WebPAnimEncoderOptions(
                minimizeSize = true,
                animParams = WebPMuxAnimParams(
                    backgroundColor = Color.TRANSPARENT,
                    loopCount = 0 
                )
            )
        )
        encoder.configure(
            config = WebPConfig(
                lossless = WebPConfig.COMPRESSION_LOSSY,
                quality = quality
            ),
            preset = WebPPreset.WEBP_PRESET_PICTURE
        )

        try {
            var timestamp = 0L
            for (frame in frames) {
                encoder.addFrame(timestamp, frame.bitmap)
                timestamp = frame.timestampMs
            }
            encoder.assemble(timestamp, Uri.fromFile(outputFile))
            encoder.release()
            cont.resume(Unit)
        } catch (e: Exception) {
            encoder.release()
            cont.resumeWithException(e)
        }
    }
}
