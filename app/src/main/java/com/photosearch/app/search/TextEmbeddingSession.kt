package com.photosearch.app.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.util.Log
import com.photosearch.app.model.ModelConfig
import com.photosearch.app.model.TensorElementType
import java.io.Closeable
import java.io.File
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.EnumSet

data class TextEmbeddingResult(
    val embedding: FloatArray,
    val prepareMs: Double,
    val inferenceMs: Double
)

enum class TextBackend {
    CPU,
    NNAPI
}

class TextEmbeddingSession(
    private val modelDir: File,
    private val config: ModelConfig,
    backend: TextBackend = TextBackend.CPU,
    private val store: PhotoIndexStore? = null
) : Closeable {
    private val textConfig = requireNotNull(config.textEncoder) { "Text encoder is not configured" }
    private val tokenizer = ClipTokenizer.fromFiles(
        modelDir = modelDir,
        config = requireNotNull(textConfig.tokenizer) { "Tokenizer is not configured" }
    )
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

        when (backend) {
            TextBackend.CPU -> {
                val numThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                setIntraOpNumThreads(numThreads)
                setInterOpNumThreads(numThreads)
                Log.i(TAG, "Text backend: CPU (threads=$numThreads)")
            }
            TextBackend.NNAPI -> {
                try {
                    addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                    Log.i(TAG, "Text backend: NNAPI (CPU_DISABLED)")
                } catch (e: Exception) {
                    // NNAPI 不可用，回退到 CPU
                    Log.w(TAG, "NNAPI failed, falling back to CPU: ${e.message}")
                    val numThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                    setIntraOpNumThreads(numThreads)
                    setInterOpNumThreads(numThreads)
                    Log.i(TAG, "Text backend: CPU fallback (threads=$numThreads)")
                }
            }
        }
    }
    private val session = env.createSession(File(modelDir, textConfig.fileName).absolutePath, options)
    private val cacheModelKey = "${config.id}:${config.version}:${textConfig.fileName}:${config.embeddingDim}"

    private val queryCache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean {
            return size > 100
        }
    }

    init {
        store?.loadAllCachedEmbeddings(cacheModelKey)?.let { cached ->
            synchronized(queryCache) {
                queryCache.putAll(cached)
            }
            Log.i(TAG, "Loaded ${cached.size} cached embeddings from SQLite")
        }
    }

    fun embed(query: String): FloatArray {
        return embedWithTiming(query).embedding
    }

    fun embedWithTiming(query: String): TextEmbeddingResult {
        synchronized(queryCache) {
            queryCache[query]?.let { cached ->
                return TextEmbeddingResult(
                    embedding = cached,
                    prepareMs = 0.0,
                    inferenceMs = 0.0
                )
            }
        }

        val prepareStarted = System.nanoTime()
        val ids = tokenizer.encode(query)
        val inputShape = longArrayOf(1, ids.size.toLong())
        val inputTensor = when (textConfig.inputDataType) {
            TensorElementType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(ids), inputShape)
            TensorElementType.INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(ids.map { it.toInt() }.toIntArray()), inputShape)
        }
        val prepareMs = (System.nanoTime() - prepareStarted) / 1_000_000.0
        inputTensor.use { tensor ->
            val inferenceStarted = System.nanoTime()
            val output = session.run(mapOf(textConfig.inputIdsName to tensor), setOf(textConfig.outputName))
            val inferenceMs = (System.nanoTime() - inferenceStarted) / 1_000_000.0
            output.use {
                val value = output.get(textConfig.outputName)
                    .orElseThrow { IllegalStateException("Missing output ${textConfig.outputName}") }
                val outTensor = value as? OnnxTensor
                    ?: error("Output ${textConfig.outputName} is not a tensor")
                val shape = (outTensor.info as TensorInfo).shape
                val dim = shape.lastOrNull()?.toInt() ?: config.embeddingDim
                val embedding = FloatArray(dim)
                val buffer = outTensor.floatBuffer
                buffer.rewind()
                buffer.get(embedding)
                if (textConfig.outputL2Normalize) VectorMath.normalizeInPlace(embedding)

                synchronized(queryCache) {
                    queryCache[query] = embedding
                }
                try {
                    store?.putCachedEmbedding(cacheModelKey, query, embedding)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist embedding cache: ${e.message}")
                }

                return TextEmbeddingResult(
                    embedding = embedding,
                    prepareMs = prepareMs,
                    inferenceMs = inferenceMs
                )
            }
        }
    }

    /**
     * Clears the in-memory query cache; persistent cache invalidation is handled by PhotoIndexStore.
     */
    fun clearQueryCache() {
        synchronized(queryCache) {
            queryCache.clear()
        }
        Log.i(TAG, "Query cache cleared")
    }

    override fun close() {
        session.close()
        options.close()
    }

    private companion object {
        const val TAG = "TextEmbedding"
    }
}
