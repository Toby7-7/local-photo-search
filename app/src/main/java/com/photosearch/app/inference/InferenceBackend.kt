package com.photosearch.app.inference

import android.content.Context
import com.photosearch.app.media.PreprocessedImage
import com.photosearch.app.model.ModelConfig
import java.io.Closeable
import java.io.File

data class BackendAvailability(
    val available: Boolean,
    val message: String = "ready"
)

data class InferenceTiming(
    val inferenceMs: Double
)

data class EmbeddingResult(
    val embeddings: FloatArray,
    val batchSize: Int,
    val embeddingDim: Int,
    val inferenceMs: Double
)

interface BackendSession : Closeable {
    val creationMs: Double
    fun run(input: PreprocessedImage): InferenceTiming
    fun embed(input: PreprocessedImage): EmbeddingResult
}

interface InferenceBackend {
    val id: String
    val displayName: String
    val flags: List<String>
    fun availability(context: Context): BackendAvailability
    fun createSession(context: Context, config: ModelConfig, modelDir: File): BackendSession
}
