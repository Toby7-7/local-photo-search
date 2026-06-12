package com.photosearch.app.search

import android.util.Log

internal object NativeVectorSearch {
    @Volatile
    private var loadAttempted = false

    @Volatile
    private var loaded = false

    fun searchTopK(
        vectors: FloatArray,
        vectorFloatOffsets: IntArray,
        dims: IntArray,
        queryEmbedding: FloatArray,
        threshold: Float,
        topK: Int
    ): VectorSearchEngine.TopKResult? {
        if (!ensureLoaded()) return null
        val limit = topK.coerceAtLeast(1)
        val scores = FloatArray(limit)
        val indexes = IntArray(limit)
        val packed = try {
            searchTopKNative(
                vectors,
                vectorFloatOffsets,
                dims,
                queryEmbedding,
                threshold,
                limit,
                scores,
                indexes
            )
        } catch (e: UnsatisfiedLinkError) {
            logFallback("Native vector search call failed, falling back to Kotlin", e)
            loaded = false
            return null
        }
        if (packed < 0L) return null
        val size = (packed and 0xFFFF_FFFFL).toInt()
        val compared = (packed ushr 32).toInt()
        return VectorSearchEngine.TopKResult(
            scores = scores,
            indexes = indexes,
            size = size,
            compared = compared,
            engine = VectorSearchEngine.ENGINE_NATIVE
        )
    }

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadAttempted) return false
        synchronized(this) {
            if (loaded) return true
            if (loadAttempted) return false
            loadAttempted = true
            loaded = try {
                System.loadLibrary("photo_search")
                true
            } catch (e: Throwable) {
                logFallback("Native vector search unavailable, falling back to Kotlin", e)
                false
            }
            return loaded
        }
    }

    private external fun searchTopKNative(
        vectors: FloatArray,
        vectorFloatOffsets: IntArray,
        dims: IntArray,
        queryEmbedding: FloatArray,
        threshold: Float,
        topK: Int,
        outScores: FloatArray,
        outIndexes: IntArray
    ): Long

    private fun logFallback(message: String, error: Throwable) {
        runCatching {
            Log.w(TAG, message, error)
        }
    }

    private const val TAG = "PhotoSearch"
}
