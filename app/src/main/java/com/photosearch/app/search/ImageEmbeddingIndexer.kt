package com.photosearch.app.search

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.photosearch.app.inference.BackendFactory
import com.photosearch.app.inference.BackendSession
import com.photosearch.app.inference.InferenceBackend
import com.photosearch.app.media.ImagePreprocessor
import com.photosearch.app.media.PreprocessedImage
import com.photosearch.app.model.ModelConfig
import com.photosearch.app.model.ModelEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.coroutineContext

class ImageEmbeddingIndexer(
    private val context: Context,
    private val store: PhotoIndexStore,
    private val vectorStore: VectorStore
) {
    private val sessionMutex = Mutex()
    private var cachedSessionKey: String? = null
    private var cachedSession: BackendSession? = null
    private var activeCachedUses: Int = 0
    private var releaseCachedWhenIdle: Boolean = false

    suspend fun chooseBackend(model: ModelEntry, preferredBatchSizes: List<Int> = listOf(8)): BackendChoice =
        withContext(Dispatchers.Default) {
            val backends = BackendFactory.all()
            val config = requireNotNull(model.config)
            val ordered = sortedBackends(backends)
            val failures = ArrayList<String>()
            val batchSize = preferredBatchSizes.firstOrNull { it > 0 } ?: 8

            fun availableChoice(backend: InferenceBackend): BackendChoice? {
                val availability = backend.availability(context)
                return if (!availability.available) {
                    failures += "${backend.id}: ${availability.message}"
                    null
                } else {
                    BackendChoice(
                        backendId = backend.id,
                        backendName = backend.displayName,
                        batchSize = batchSize,
                        message = "selected ${backend.displayName} batch $batchSize"
                    )
                }
            }

            val accelerated = ordered.filter { it.id == "qnn_htp" || "nnapi" in it.id }
            val fallback = ordered.filterNot { it in accelerated }
            var available: BackendChoice? = null
            for (backend in accelerated) {
                available = availableChoice(backend)
                if (available != null) break
            }
            if (available == null && ordered.any { it.id == "qnn_htp" }) {
                error("QNN HTP unavailable; CPU indexing disabled. ${failures.joinToString("; ")}")
            }
            for (backend in fallback) {
                if (available != null) break
                available = availableChoice(backend)
            }

            val selected = available
                ?: error("No available ONNX Runtime backend. ${failures.joinToString("; ")}")
            selected.also {
                require(config.embeddingDim > 0)
            }
        }

    suspend fun preload(model: ModelEntry, backendId: String): Double =
        withContext(Dispatchers.Default) {
            val started = SystemClock.elapsedRealtimeNanos()
            val creationMs = sessionMutex.withLock {
                val config = requireNotNull(model.config)
                val backend = BackendFactory.all().firstOrNull { it.id == backendId }
                    ?: BackendFactory.all().first { it.availability(context).available }
                val key = sessionKey(model, backend.id, config)
                val existing = cachedSession
                if (existing != null && cachedSessionKey == key) {
                    0.0
                } else {
                    if (activeCachedUses == 0) {
                        existing?.close()
                        cachedSession = null
                        cachedSessionKey = null
                    }
                    val session = backend.createSession(context, config, File(model.modelDirPath))
                    cachedSession = session
                    cachedSessionKey = key
                    releaseCachedWhenIdle = false
                    session.creationMs
                }
            }
            Log.i(TAG, "preload index model backend=$backendId create=${creationMs.ms()} total=${elapsedMs(started).ms()}")
            creationMs
        }

    suspend fun releaseCachedSession() {
        withContext(Dispatchers.Default) {
            sessionMutex.withLock {
                if (activeCachedUses > 0) {
                    releaseCachedWhenIdle = true
                } else {
                    cachedSession?.close()
                    cachedSession = null
                    cachedSessionKey = null
                    releaseCachedWhenIdle = false
                }
            }
            Log.i(TAG, "released index model")
        }
    }

    suspend fun indexPending(
        model: ModelEntry,
        backendId: String,
        batchSize: Int,
        keepSession: Boolean,
        onStats: (IndexStats) -> Unit
    ) = withContext(Dispatchers.Default) {
        val config = requireNotNull(model.config)
        val backend = BackendFactory.all().firstOrNull { it.id == backendId }
            ?: BackendFactory.all().first { it.availability(context).available }
        var currentStats = store.stats()
        val lease = acquireSession(model, backend, config, keepSession)
        try {
            val session = lease.session
            var current = prepareNextBatch(config, batchSize, emptySet()) ?: return@withContext
            var lastCompletionNanos: Long? = null
            while (true) {
                coroutineContext.ensureActive()
                val next: Deferred<PreparedBatch?> = async(Dispatchers.IO) {
                    prepareNextBatch(config, batchSize, current.mediaIds)
                }
                val result = processPreparedBatch(
                    model = model,
                    config = config,
                    session = session,
                    backendId = backend.id,
                    batchSize = batchSize,
                    prepared = current,
                    previousCompletionNanos = lastCompletionNanos
                )
                lastCompletionNanos = result.completedNanos
                currentStats = currentStats.copy(
                    indexed = currentStats.indexed + result.indexedNow,
                    pending = (currentStats.pending - current.records.size).coerceAtLeast(0),
                    failed = currentStats.failed + result.failedNow
                )
                val stats = currentStats.copy(
                    running = true,
                    backendId = backend.id,
                    batchSize = batchSize,
                    recentImagesPerSecond = result.wallImagesPerSecond,
                    recentInferenceImagesPerSecond = result.inferenceImagesPerSecond,
                    recentBatchMs = result.workMs,
                    recentWorkMs = result.workMs,
                    recentWallMs = result.wallMs,
                    recentInferenceMs = result.inferenceMs,
                    recentPreprocessMs = result.preprocessMs,
                    recentDecodeMs = result.decodeMs,
                    recentTensorMs = result.tensorMs,
                    recentWriteMs = result.writeMs
                )
                onStats(stats)
                current = next.await() ?: break
            }
        } finally {
            releaseSession(lease)
        }
    }

    suspend fun indexRecords(
        model: ModelEntry,
        backendId: String,
        records: List<PhotoRecord>,
        batchSize: Int,
        keepSession: Boolean,
        onStats: (IndexStats) -> Unit
    ) = withContext(Dispatchers.Default) {
        if (records.isEmpty()) return@withContext
        val config = requireNotNull(model.config)
        val backend = BackendFactory.all().firstOrNull { it.id == backendId }
            ?: BackendFactory.all().first { it.availability(context).available }
        val requestedBatchSize = batchSize.coerceAtLeast(1)
        var currentStats = store.stats()
        val lease = acquireSession(model, backend, config, keepSession)
        try {
            val session = lease.session
            var lastCompletionNanos: Long? = null
            for (chunk in records.chunked(requestedBatchSize)) {
                coroutineContext.ensureActive()
                val prepared = prepareRecords(config, chunk)
                val result = processPreparedBatch(
                    model = model,
                    config = config,
                    session = session,
                    backendId = backend.id,
                    batchSize = requestedBatchSize,
                    prepared = prepared,
                    previousCompletionNanos = lastCompletionNanos
                )
                lastCompletionNanos = result.completedNanos
                currentStats = currentStats.copy(
                    indexed = currentStats.indexed + result.indexedNow,
                    failed = currentStats.failed - result.indexedNow + result.failedNow
                )
                val stats = currentStats.copy(
                    running = true,
                    backendId = backend.id,
                    batchSize = requestedBatchSize,
                    recentImagesPerSecond = result.wallImagesPerSecond,
                    recentInferenceImagesPerSecond = result.inferenceImagesPerSecond,
                    recentBatchMs = result.workMs,
                    recentWorkMs = result.workMs,
                    recentWallMs = result.wallMs,
                    recentInferenceMs = result.inferenceMs,
                    recentPreprocessMs = result.preprocessMs,
                    recentDecodeMs = result.decodeMs,
                    recentTensorMs = result.tensorMs,
                    recentWriteMs = result.writeMs
                )
                onStats(stats)
            }
        } finally {
            releaseSession(lease)
        }
    }

    private suspend fun acquireSession(
        model: ModelEntry,
        backend: InferenceBackend,
        config: ModelConfig,
        keepSession: Boolean
    ): BackendSessionLease =
        sessionMutex.withLock {
            val key = sessionKey(model, backend.id, config)
            val existing = cachedSession
            if (existing != null && cachedSessionKey == key) {
                if (keepSession) {
                    activeCachedUses += 1
                    releaseCachedWhenIdle = false
                    return@withLock BackendSessionLease(existing, cached = true)
                }
                cachedSession = null
                cachedSessionKey = null
                releaseCachedWhenIdle = false
                return@withLock BackendSessionLease(existing, cached = false)
            }
            if (existing != null && activeCachedUses == 0) {
                existing.close()
                cachedSession = null
                cachedSessionKey = null
            }
            val created = backend.createSession(context, config, File(model.modelDirPath))
            if (keepSession) {
                cachedSession = created
                cachedSessionKey = key
                activeCachedUses += 1
                releaseCachedWhenIdle = false
                BackendSessionLease(created, cached = true)
            } else {
                BackendSessionLease(created, cached = false)
            }
        }

    private suspend fun releaseSession(lease: BackendSessionLease) {
        sessionMutex.withLock {
            if (!lease.cached) {
                lease.session.close()
                return@withLock
            }
            activeCachedUses = (activeCachedUses - 1).coerceAtLeast(0)
            if (activeCachedUses == 0 && releaseCachedWhenIdle) {
                cachedSession?.close()
                cachedSession = null
                cachedSessionKey = null
                releaseCachedWhenIdle = false
            }
        }
    }

    private fun sessionKey(model: ModelEntry, backendId: String, config: ModelConfig): String =
        "${model.modelDirPath}:${config.version}:$backendId"

    private suspend fun prepareNextBatch(
        config: ModelConfig,
        batchSize: Int,
        excludedIds: Set<Long>
    ): PreparedBatch? {
        coroutineContext.ensureActive()
        val records = withContext(Dispatchers.IO) {
            store.pending(batchSize * 3)
                .asSequence()
                .filterNot { it.mediaId in excludedIds }
                .take(batchSize)
                .toList()
        }
        if (records.isEmpty()) return null
        return prepareRecords(config, records)
    }

    private suspend fun prepareRecords(
        config: ModelConfig,
        records: List<PhotoRecord>
    ): PreparedBatch {
        coroutineContext.ensureActive()
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            val input = ImagePreprocessor.preprocessBatch(
                context = context,
                uris = records.map { it.parsedUri },
                encoder = config.imageEncoder
            )
            PreparedBatch(
                records = records,
                input = input,
                error = null,
                startedNanos = started,
                finishedNanos = SystemClock.elapsedRealtimeNanos()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            PreparedBatch(
                records = records,
                input = null,
                error = error,
                startedNanos = started,
                finishedNanos = SystemClock.elapsedRealtimeNanos()
            )
        }
    }

    private fun processPreparedBatch(
        model: ModelEntry,
        config: ModelConfig,
        session: BackendSession,
        backendId: String,
        batchSize: Int,
        prepared: PreparedBatch,
        previousCompletionNanos: Long?
    ): BatchIndexResult {
        val processStarted = SystemClock.elapsedRealtimeNanos()
        var inferenceMs = 0.0
        var preprocessMs = prepared.input?.preprocessMs
            ?: ((prepared.finishedNanos - prepared.startedNanos) / 1_000_000.0)
        var decodeMs = prepared.input?.decodeMs ?: 0.0
        var tensorMs = prepared.input?.tensorMs ?: 0.0
        var writeMs = 0.0
        var indexedNow = 0
        var failedNow = 0
        try {
            val input = prepared.input
            if (input == null) {
                throw prepared.error ?: IllegalStateException("preprocess failed")
            }
            val result = session.embed(input)
            inferenceMs = result.inferenceMs
            require(result.embeddingDim == config.embeddingDim) {
                "Embedding dim ${result.embeddingDim}, expected ${config.embeddingDim}"
            }
            val writeStarted = SystemClock.elapsedRealtimeNanos()
            if (config.imageEncoder.outputL2Normalize) {
                for (index in 0 until result.batchSize) {
                    VectorMath.normalizeInPlace(
                        values = result.embeddings,
                        offset = index * result.embeddingDim,
                        length = result.embeddingDim
                    )
                }
            }
            val offsets = vectorStore.appendBatch(
                data = result.embeddings,
                vectorDim = result.embeddingDim,
                count = result.batchSize
            )
            store.markIndexedBatch(
                items = prepared.records.mapIndexed { index, record ->
                    IndexedUpdate(
                        mediaId = record.mediaId,
                        vectorOffset = offsets[index],
                        vectorDim = result.embeddingDim
                    )
                },
                modelVersion = modelVersion(config.id, config.version)
            )
            writeMs = (SystemClock.elapsedRealtimeNanos() - writeStarted) / 1_000_000.0
            indexedNow = prepared.records.size
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            prepared.records.forEach { record ->
                store.markFailed(record.mediaId, error.message ?: error::class.java.simpleName)
            }
            failedNow = prepared.records.size
        }
        val completed = SystemClock.elapsedRealtimeNanos()
        val postPreprocessMs = (completed - processStarted) / 1_000_000.0
        val workMs = preprocessMs + postPreprocessMs
        val wallMs = previousCompletionNanos
            ?.let { (completed - it) / 1_000_000.0 }
            ?: ((completed - prepared.startedNanos) / 1_000_000.0)
        val count = prepared.records.size
        val wallIps = if (wallMs > 0.0) count * 1000.0 / wallMs else null
        val inferIps = if (inferenceMs > 0.0) count * 1000.0 / inferenceMs else null
        return BatchIndexResult(
            indexedNow = indexedNow,
            failedNow = failedNow,
            completedNanos = completed,
            wallImagesPerSecond = wallIps,
            inferenceImagesPerSecond = inferIps,
            workMs = workMs,
            wallMs = wallMs,
            inferenceMs = inferenceMs,
            preprocessMs = preprocessMs,
            decodeMs = decodeMs,
            tensorMs = tensorMs,
            writeMs = writeMs
        )
    }

    private fun sortedBackends(backends: List<InferenceBackend>): List<InferenceBackend> =
        backends.sortedWith(
            compareByDescending<InferenceBackend> { it.id == "qnn_htp" }
                .thenByDescending { "nnapi" in it.id }
        )

    private fun elapsedMs(startedNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000.0

    private fun Double.ms(): String = "%.1fms".format(this)

    companion object {
        private const val TAG = "PhotoSearch"

        fun modelVersion(id: String, version: String): String = "$id:$version"
    }
}

private data class BackendSessionLease(
    val session: BackendSession,
    val cached: Boolean
)

private data class PreparedBatch(
    val records: List<PhotoRecord>,
    val input: PreprocessedImage?,
    val error: Throwable?,
    val startedNanos: Long,
    val finishedNanos: Long
) {
    val mediaIds: Set<Long> = records.mapTo(HashSet()) { it.mediaId }
}

private data class BatchIndexResult(
    val indexedNow: Int,
    val failedNow: Int,
    val completedNanos: Long,
    val wallImagesPerSecond: Double?,
    val inferenceImagesPerSecond: Double?,
    val workMs: Double,
    val wallMs: Double,
    val inferenceMs: Double,
    val preprocessMs: Double,
    val decodeMs: Double,
    val tensorMs: Double,
    val writeMs: Double
)
