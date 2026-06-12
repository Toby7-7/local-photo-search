package com.photosearch.app.model

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ModelRegistry(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    val modelsRoot: File
        get() = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    fun scan(): List<ModelEntry> {
        val templatesById = TemplateModels.all.associateBy { it.id }
        val discovered = linkedMapOf<String, ModelEntry>()

        templatesById.values.forEach { template ->
            discovered[template.id] = entryFor(template.id, template)
        }

        modelsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { dir ->
                val configFile = File(dir, "model.json")
                if (!configFile.exists()) return@forEach
                runCatching {
                    json.decodeFromString<ModelConfig>(configFile.readText())
                }.fold(
                    onSuccess = { config ->
                        val template = templatesById[config.id] ?: config
                        discovered[config.id] = entryFor(config.id, template, config, dir)
                    },
                    onFailure = { error ->
                        val id = dir.name
                        val template = templatesById[id] ?: fallbackTemplate(id)
                        discovered[id] = ModelEntry(
                            id = id,
                            displayName = template.displayName,
                            family = template.family,
                            modelDirPath = dir.absolutePath,
                            config = null,
                            templateConfig = template,
                            available = false,
                            missingFiles = emptyList(),
                            parseError = error.message ?: error::class.java.simpleName
                        )
                    }
                )
            }

        return discovered.values.sortedWith(compareBy<ModelEntry> { !it.available }.thenBy { it.id })
    }

    fun ensureTemplateFolders(): List<File> =
        TemplateModels.all.map { template ->
            val dir = File(modelsRoot, template.id).also { it.mkdirs() }
            val configFile = File(dir, "model.json")
            if (!configFile.exists()) {
                configFile.writeText(json.encodeToString(template))
            }
            dir
        }

    /**
     * Model files are not bundled in APK assets.
     * Push model files manually to external storage using tools/prepare_photo_search_model.py,
     * then adb push to /sdcard/Android/data/com.photosearch.app/files/models/mobileclip2-s0/.
     */
    fun copyModelAssets(context: Context) {
        // No-op: models delivered via manual adb push, not APK assets
    }

    fun modelDirFor(modelId: String): File = File(modelsRoot, modelId).also { it.mkdirs() }

    private fun entryFor(
        id: String,
        template: ModelConfig,
        parsedConfig: ModelConfig? = null,
        explicitDir: File? = null
    ): ModelEntry {
        val dir = explicitDir ?: File(modelsRoot, id)
        val config = parsedConfig ?: runCatching {
            val configFile = File(dir, "model.json")
            if (configFile.exists()) json.decodeFromString<ModelConfig>(configFile.readText()) else null
        }.getOrNull()
        val effectiveConfig = config?.withTemplateDefaults(template) ?: template
        val missing = requiredFiles(effectiveConfig).filterNot { File(dir, it).exists() }
        val textMissing = textFiles(effectiveConfig).filterNot { File(dir, it).exists() }
        val configExists = File(dir, "model.json").exists()
        val imageMissing = imageFiles(effectiveConfig).filterNot { File(dir, it).exists() }
        return ModelEntry(
            id = effectiveConfig.id,
            displayName = effectiveConfig.displayName,
            family = effectiveConfig.family,
            modelDirPath = dir.absolutePath,
            config = config,
            templateConfig = template,
            available = configExists && imageMissing.isEmpty(),
            missingFiles = if (configExists) imageMissing else listOf("model.json") + imageMissing,
            textAvailable = configExists && textMissing.isEmpty() && effectiveConfig.textEncoder != null,
            textMissingFiles = if (effectiveConfig.textEncoder == null) {
                emptyList()
            } else {
                textMissing
            }
        )
    }

    private fun requiredFiles(config: ModelConfig): List<String> = buildList {
        addAll(imageFiles(config))
        addAll(textFiles(config))
    }.distinct()

    private fun imageFiles(config: ModelConfig): List<String> = buildList {
        add(config.imageEncoder.fileName)
    }.distinct()

    private fun textFiles(config: ModelConfig): List<String> = buildList {
        config.textEncoder?.let { text ->
            add(text.fileName)
            text.tokenizer?.tokenizerFileName?.let(::add)
            if (text.tokenizer?.tokenizerFileName == null) {
                text.tokenizer?.vocabFileName?.let(::add)
                text.tokenizer?.mergesFileName?.let(::add)
            }
        }
    }.distinct()

    private fun fallbackTemplate(id: String): ModelConfig =
        ModelConfig(
            id = id,
            displayName = id,
            family = "custom",
            embeddingDim = 512,
            imageEncoder = EncoderConfig(
                fileName = "image_encoder.onnx",
                inputName = "pixel_values",
                outputName = "image_embeds",
                inputWidth = 224,
                inputHeight = 224
            )
        )

    private fun ModelConfig.withTemplateDefaults(template: ModelConfig): ModelConfig =
        copy(
            textEncoder = textEncoder ?: template.textEncoder
        )
}
