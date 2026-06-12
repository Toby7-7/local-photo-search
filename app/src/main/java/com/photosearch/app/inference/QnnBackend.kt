package com.photosearch.app.inference

import android.content.Context
import android.os.Build
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import com.photosearch.app.model.ModelConfig
import java.io.File

class QnnBackend : InferenceBackend {
    override val id: String = "qnn_htp"
    override val displayName: String = "QNN HTP"
    override val flags: List<String> = listOf(
        "backend_type=htp",
        "htp_performance_mode=sustained_high_performance",
        "enable_htp_fp16_precision=1",
        "cpu_ep_fallback=allowed"
    )

    override fun availability(context: Context): BackendAvailability {
        if (Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" }) {
            return BackendAvailability(false, "QNN HTP requires arm64-v8a")
        }

        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val bundled = listOf("libQnnHtp.so", "libQnnSystem.so").filter { File(libDir, it).exists() }
        return if (bundled.containsAll(listOf("libQnnHtp.so", "libQnnSystem.so"))) {
            BackendAvailability(true, "QNN runtime bundled")
        } else {
            BackendAvailability(
                available = true,
                message = "QNN runtime not visible in nativeLibraryDir; trying ORT/QNN loader"
            )
        }
    }

    override fun createSession(context: Context, config: ModelConfig, modelDir: File): BackendSession {
        val available = availability(context)
        require(available.available) { available.message }
        val modelFile = File(modelDir, config.imageEncoder.fileName)
        require(modelFile.exists()) { "Missing model file: ${modelFile.absolutePath}" }

        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            try {
                addQnn(providerOptions(context))
            } catch (error: OrtException) {
                throw IllegalStateException("QNN registration failed: ${error.message}", error)
            }
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return createOrtSession(env, options, modelFile, config.imageEncoder.outputName)
    }

    private fun providerOptions(context: Context): Map<String, String> {
        return mapOf(
            "backend_type" to "htp",
            "htp_performance_mode" to "sustained_high_performance",
            "qnn_context_priority" to "high",
            "enable_htp_fp16_precision" to "1",
            "offload_graph_io_quantization" to "0"
        )
    }
}
