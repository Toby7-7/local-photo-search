package com.photosearch.app.model

object TemplateModels {
    val all: List<ModelConfig> = listOf(
        ModelConfig(
            id = "mobileclip2-s0",
            displayName = "MobileCLIP2-S0",
            family = "MobileCLIP2",
            version = "photo-search-s0-v2-mobileclip-preprocess",
            embeddingDim = 512,
            imageEncoder = mobileClipImageEncoder("image_encoder.onnx", 256),
            textEncoder = TextEncoderConfig(
                fileName = "text_encoder.onnx",
                inputIdsName = "input_ids",
                attentionMaskName = null,
                outputName = "text_embeds",
                tokenizer = TokenizerConfig(
                    type = "clip_bpe",
                    vocabFileName = "vocab.json",
                    mergesFileName = "merges.txt",
                    maxLength = 77
                )
            )
        )
    )

    private fun mobileClipImageEncoder(fileName: String, size: Int): EncoderConfig =
        EncoderConfig(
            fileName = fileName,
            inputName = "pixel_values",
            outputName = "image_embeds",
            inputWidth = size,
            inputHeight = size,
            layout = TensorLayout.NCHW,
            mean = listOf(0f, 0f, 0f),
            std = listOf(1f, 1f, 1f)
        )
}
