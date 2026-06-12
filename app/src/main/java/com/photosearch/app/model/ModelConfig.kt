package com.photosearch.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelConfig(
    val id: String,
    val displayName: String,
    val family: String,
    val version: String = "unknown",
    val embeddingDim: Int,
    val imageEncoder: EncoderConfig,
    val textEncoder: TextEncoderConfig? = null,
    val notes: String = ""
)

@Serializable
data class EncoderConfig(
    val fileName: String,
    val inputName: String,
    val outputName: String,
    val inputWidth: Int,
    val inputHeight: Int,
    val channels: Int = 3,
    val layout: TensorLayout = TensorLayout.NCHW,
    val mean: List<Float> = listOf(0.48145466f, 0.4578275f, 0.40821073f),
    val std: List<Float> = listOf(0.26862954f, 0.26130258f, 0.27577711f),
    val outputL2Normalize: Boolean = true
)

@Serializable
data class TextEncoderConfig(
    val fileName: String,
    val inputIdsName: String = "input_ids",
    val attentionMaskName: String? = "attention_mask",
    val outputName: String,
    val tokenizer: TokenizerConfig? = null,
    val inputDataType: TensorElementType = TensorElementType.INT64,
    val outputL2Normalize: Boolean = true
)

@Serializable
data class TokenizerConfig(
    val type: String,
    val tokenizerFileName: String? = null,
    val vocabFileName: String? = null,
    val mergesFileName: String? = null,
    val maxLength: Int = 77,
    val bosToken: String = "<|startoftext|>",
    val eosToken: String = "<|endoftext|>",
    val padTokenId: Int = 0
)

@Serializable
enum class TensorLayout {
    @SerialName("NCHW")
    NCHW,

    @SerialName("NHWC")
    NHWC
}

@Serializable
enum class TensorElementType {
    @SerialName("INT64")
    INT64,

    @SerialName("INT32")
    INT32
}

data class ModelEntry(
    val id: String,
    val displayName: String,
    val family: String,
    val modelDirPath: String,
    val config: ModelConfig?,
    val templateConfig: ModelConfig,
    val available: Boolean,
    val missingFiles: List<String>,
    val textAvailable: Boolean = false,
    val textMissingFiles: List<String> = emptyList(),
    val parseError: String? = null
) {
    val statusText: String
        get() = when {
            parseError != null -> "config error"
            available && textAvailable -> "image + text ready"
            available -> "image ready"
            missingFiles.isNotEmpty() -> "missing ${missingFiles.joinToString()}"
            else -> "template"
        }
}
