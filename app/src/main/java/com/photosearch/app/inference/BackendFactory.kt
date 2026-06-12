package com.photosearch.app.inference

import ai.onnxruntime.providers.NNAPIFlags
import com.photosearch.app.BuildConfig
import java.util.EnumSet

object BackendFactory {
    fun all(): List<InferenceBackend> = when (BuildConfig.ORT_RUNTIME) {
        "qnn" -> listOf(
            OrtCpuBackend(
                id = "ort_qnn_pkg_cpu_auto_threads",
                displayName = "ORT CPU (QNN pkg)"
            ),
            QnnBackend()
        )

        else -> listOf(
            OrtCpuBackend(
                id = "ort_cpu_auto_threads",
                displayName = "ORT CPU"
            ),
            OrtNnapiBackend(
                id = "ort_nnapi_cpu_disabled",
                displayName = "ORT NNAPI",
                nnapiFlags = EnumSet.of(NNAPIFlags.CPU_DISABLED)
            )
        )
    }
}
