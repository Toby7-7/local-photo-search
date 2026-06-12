package com.photosearch.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Size
import com.photosearch.app.model.EncoderConfig
import com.photosearch.app.model.TensorLayout
import java.nio.FloatBuffer

data class PreprocessedImage(
    val uris: List<Uri>,
    val mediaKey: String,
    val inputName: String,
    val data: FloatArray,
    val shape: LongArray,
    val preprocessMs: Double,
    val decodeMs: Double = 0.0,
    val tensorMs: Double = 0.0
) {
    val batchSize: Int
        get() = uris.size
}

private data class PreprocessTiming(
    val decodeMs: Double,
    val tensorMs: Double
)

object ImagePreprocessor {
    fun preprocess(context: Context, uri: Uri, encoder: EncoderConfig): PreprocessedImage {
        return preprocessBatch(context, listOf(uri), encoder)
    }

    fun preprocessBatch(context: Context, uris: List<Uri>, encoder: EncoderConfig): PreprocessedImage {
        require(uris.isNotEmpty()) { "Batch must contain at least one image" }
        val started = SystemClock.elapsedRealtimeNanos()
        val singleImageSize = encoder.channels * encoder.inputWidth * encoder.inputHeight
        val data = FloatArray(singleImageSize * uris.size)
        val keys = mutableListOf<String>()
        var decodeMs = 0.0
        var tensorMs = 0.0
        uris.forEachIndexed { batchIndex, uri ->
            val timing = preprocessInto(
                context = context,
                uri = uri,
                encoder = encoder,
                output = data,
                outputOffset = batchIndex * singleImageSize
            )
            decodeMs += timing.decodeMs
            tensorMs += timing.tensorMs
            keys += mediaKey(uri)
        }
        val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        return PreprocessedImage(
            uris = uris,
            mediaKey = keys.joinToString("+"),
            inputName = encoder.inputName,
            data = data,
            shape = tensorShape(encoder, uris.size),
            preprocessMs = elapsed,
            decodeMs = decodeMs,
            tensorMs = tensorMs
        )
    }

    private fun preprocessInto(
        context: Context,
        uri: Uri,
        encoder: EncoderConfig,
        output: FloatArray,
        outputOffset: Int
    ): PreprocessTiming {
        val decodeStarted = SystemClock.elapsedRealtimeNanos()
        val bitmap = decodeThumbnailOrImage(context, uri, encoder)
        val scaled = if (bitmap.width == encoder.inputWidth && bitmap.height == encoder.inputHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, encoder.inputWidth, encoder.inputHeight, true)
        }
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        val decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStarted) / 1_000_000.0

        val tensorStarted = SystemClock.elapsedRealtimeNanos()
        val width = encoder.inputWidth
        val height = encoder.inputHeight
        val channels = encoder.channels
        val plane = width * height
        val pixels = IntArray(encoder.inputWidth * encoder.inputHeight)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        scaled.recycle()

        val mean = normalizedTriplet(encoder.mean, 0f)
        val std = normalizedTriplet(encoder.std, 1f)
        if (channels == 3 && mean[0] == 0f && mean[1] == 0f && mean[2] == 0f &&
            std[0] == 1f && std[1] == 1f && std[2] == 1f
        ) {
            when (encoder.layout) {
                TensorLayout.NCHW -> {
                    val rBase = outputOffset
                    val gBase = outputOffset + plane
                    val bBase = outputOffset + plane * 2
                    for (index in pixels.indices) {
                        val pixel = pixels[index]
                        output[rBase + index] = ((pixel shr 16) and 0xFF) / 255f
                        output[gBase + index] = ((pixel shr 8) and 0xFF) / 255f
                        output[bBase + index] = (pixel and 0xFF) / 255f
                    }
                }
                TensorLayout.NHWC -> {
                    var out = outputOffset
                    for (pixel in pixels) {
                        output[out++] = ((pixel shr 16) and 0xFF) / 255f
                        output[out++] = ((pixel shr 8) and 0xFF) / 255f
                        output[out++] = (pixel and 0xFF) / 255f
                    }
                }
            }
        } else {
            when (encoder.layout) {
                TensorLayout.NCHW -> {
                    for (index in pixels.indices) {
                        val pixel = pixels[index]
                        output[outputOffset + index] =
                            (((pixel shr 16) and 0xFF) / 255f - mean[0]) / std[0]
                        if (channels > 1) {
                            output[outputOffset + plane + index] =
                                (((pixel shr 8) and 0xFF) / 255f - mean[1]) / std[1]
                        }
                        if (channels > 2) {
                            output[outputOffset + plane * 2 + index] =
                                ((pixel and 0xFF) / 255f - mean[2]) / std[2]
                        }
                    }
                }
                TensorLayout.NHWC -> {
                    var out = outputOffset
                    for (pixel in pixels) {
                        output[out++] = (((pixel shr 16) and 0xFF) / 255f - mean[0]) / std[0]
                        if (channels > 1) {
                            output[out++] = (((pixel shr 8) and 0xFF) / 255f - mean[1]) / std[1]
                        }
                        if (channels > 2) {
                            output[out++] = ((pixel and 0xFF) / 255f - mean[2]) / std[2]
                        }
                    }
                }
            }
        }
        val tensorMs = (SystemClock.elapsedRealtimeNanos() - tensorStarted) / 1_000_000.0
        return PreprocessTiming(decodeMs = decodeMs, tensorMs = tensorMs)
    }

    private fun decodeThumbnailOrImage(context: Context, uri: Uri, encoder: EncoderConfig): Bitmap {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                return context.contentResolver.loadThumbnail(
                    uri,
                    Size(encoder.inputWidth, encoder.inputHeight),
                    null
                )
            }
        }
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            decoder.setTargetSize(encoder.inputWidth, encoder.inputHeight)
        }
    }

    fun tensorShape(encoder: EncoderConfig, batchSize: Int = 1): LongArray =
        when (encoder.layout) {
            TensorLayout.NCHW -> longArrayOf(
                batchSize.toLong(),
                encoder.channels.toLong(),
                encoder.inputHeight.toLong(),
                encoder.inputWidth.toLong()
            )
            TensorLayout.NHWC -> longArrayOf(
                batchSize.toLong(),
                encoder.inputHeight.toLong(),
                encoder.inputWidth.toLong(),
                encoder.channels.toLong()
            )
        }

    fun asFloatBuffer(image: PreprocessedImage): FloatBuffer = FloatBuffer.wrap(image.data)

    private fun normalizedTriplet(values: List<Float>, fallback: Float): FloatArray =
        FloatArray(3) { index -> values.getOrNull(index) ?: fallback }

    private fun mediaKey(uri: Uri): String = uri.toString().hashCode().toUInt().toString(16)
}
