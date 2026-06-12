package com.photosearch.app.inference

import android.content.Context
import android.os.Build
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import com.photosearch.app.media.ImagePreprocessor
import com.photosearch.app.media.PreprocessedImage
import com.photosearch.app.model.ModelConfig
import java.io.File
import java.util.EnumSet

class OrtCpuBackend(
    override val id: String,
    override val displayName: String,
    private val intraOpThreads: Int? = null
) : InferenceBackend {
    override val flags: List<String> =
        if (intraOpThreads == null) listOf("threads=auto") else listOf("threads=$intraOpThreads")

    override fun availability(context: Context): BackendAvailability = BackendAvailability(true)

    override fun createSession(context: Context, config: ModelConfig, modelDir: File): BackendSession {
        val modelFile = File(modelDir, config.imageEncoder.fileName)
        require(modelFile.exists()) { "Missing model file: ${modelFile.absolutePath}" }
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            intraOpThreads?.let(::setIntraOpNumThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return createOrtSession(env, options, modelFile, config.imageEncoder.outputName)
    }
}

class OrtNnapiBackend(
    override val id: String,
    override val displayName: String,
    private val nnapiFlags: EnumSet<NNAPIFlags>
) : InferenceBackend {
    override val flags: List<String> = nnapiFlags.map { it.name }.ifEmpty { listOf("default") }

    override fun availability(context: Context): BackendAvailability =
        if (Build.VERSION.SDK_INT >= 27) {
            BackendAvailability(true)
        } else {
            BackendAvailability(false, "NNAPI requires Android 8.1+")
        }

    override fun createSession(context: Context, config: ModelConfig, modelDir: File): BackendSession {
        val available = availability(context)
        require(available.available) { available.message }
        val modelFile = File(modelDir, config.imageEncoder.fileName)
        require(modelFile.exists()) { "Missing model file: ${modelFile.absolutePath}" }
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            try {
                addNnapi(nnapiFlags)
            } catch (error: OrtException) {
                throw IllegalStateException("NNAPI registration failed: ${error.message}", error)
            }
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return createOrtSession(env, options, modelFile, config.imageEncoder.outputName)
    }
}

internal fun createOrtSession(
    env: OrtEnvironment,
    options: OrtSession.SessionOptions,
    modelFile: File,
    outputName: String
): BackendSession {
    val started = SystemClock.elapsedRealtimeNanos()
    val session = env.createSession(modelFile.absolutePath, options)
    val creationMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
    return OrtBackendSession(env, session, options, creationMs, outputName)
}

private class OrtBackendSession(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val options: OrtSession.SessionOptions,
    override val creationMs: Double,
    private val outputName: String
) : BackendSession {
    override fun run(input: PreprocessedImage): InferenceTiming {
        val result = runInternal(input, keepEmbedding = false)
        return InferenceTiming(inferenceMs = result.inferenceMs)
    }

    override fun embed(input: PreprocessedImage): EmbeddingResult =
        runInternal(input, keepEmbedding = true)

    private fun runInternal(input: PreprocessedImage, keepEmbedding: Boolean): EmbeddingResult {
        val started = SystemClock.elapsedRealtimeNanos()
        val tensor = OnnxTensor.createTensor(env, ImagePreprocessor.asFloatBuffer(input), input.shape)
        var embeddings = FloatArray(0)
        var embeddingDim = 0
        tensor.use {
            val output = session.run(mapOf(input.inputName to tensor), setOf(outputName))
            output.use {
                if (keepEmbedding) {
                    val value = output.get(outputName)
                        .orElseThrow { IllegalStateException("Missing output $outputName") }
                    val outTensor = value as? OnnxTensor
                        ?: error("Output $outputName is not a tensor")
                    val info = outTensor.info as TensorInfo
                    val shape = info.shape
                    embeddingDim = shape.lastOrNull()?.toInt() ?: 0
                    require(embeddingDim > 0) { "Invalid embedding shape ${shape.contentToString()}" }
                    val count = input.batchSize * embeddingDim
                    val buffer = outTensor.floatBuffer
                    embeddings = FloatArray(count)
                    buffer.rewind()
                    buffer.get(embeddings)
                }
            }
        }
        return EmbeddingResult(
            embeddings = embeddings,
            batchSize = input.batchSize,
            embeddingDim = embeddingDim,
            inferenceMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        )
    }

    override fun close() {
        session.close()
        options.close()
    }
}
