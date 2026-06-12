package com.photosearch.app.search

import android.os.SystemClock
import android.util.Log
import com.photosearch.app.model.ModelEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.min

class PhotoSearcher(
    private val store: PhotoIndexStore,
    private val vectorStore: VectorStore
) {
    private var textSession: TextEmbeddingSession? = null
    private var textSessionKey: String? = null
    private val sessionMutex = Mutex()

    // --- In-memory cache for vector metadata (avoids repeated SQLite reads) ---
    @Volatile
    private var cachedFloatOffsets: IntArray? = null
    @Volatile
    private var cachedDims: IntArray? = null
    @Volatile
    private var cachedRecords: List<PhotoRecord>? = null
    @Volatile
    private var cachedVectors: FloatArray? = null
    @Volatile
    private var cachedFingerprint: VectorCacheFingerprint? = null
    private val cacheLock = Any()

    suspend fun preload(model: ModelEntry): Double =
        withContext(Dispatchers.Default) {
            val started = SystemClock.elapsedRealtimeNanos()
            val loadMs = sessionMutex.withLock {
                val existing = textSession
                val key = sessionKey(model)
                if (existing != null && textSessionKey == key) {
                    0.0
                } else {
                    existing?.close()
                    val createStarted = SystemClock.elapsedRealtimeNanos()
                    textSession = TextEmbeddingSession(File(model.modelDirPath), requireNotNull(model.config), TextBackend.CPU, store)
                    textSessionKey = key
                    elapsedMs(createStarted)
                }
            }
            Log.i(TAG, "preload search model load=${loadMs.ms()} total=${elapsedMs(started).ms()}")
            loadMs
        }

    suspend fun release() {
        withContext(Dispatchers.Default) {
            sessionMutex.withLock {
                textSession?.close()
                textSession = null
                textSessionKey = null
            }
            Log.i(TAG, "released search model")
        }
    }

    fun invalidateVectorCache() {
        synchronized(cacheLock) {
            cachedFloatOffsets = null
            cachedDims = null
            cachedRecords = null
            cachedVectors = null
            cachedFingerprint = null
        }
        Log.i(TAG, "vector cache invalidated")
    }

    /**
     * Invalidate both vector cache and text query cache.
     * Use this for model changes or full index rebuilds, not ordinary incremental indexing.
     */
    suspend fun invalidateCache() {
        invalidateVectorCache()
        sessionMutex.withLock {
            textSession?.clearQueryCache()
        }
        store.clearTextEmbeddingCache()
        Log.i(TAG, "search caches invalidated")
    }

    /**
     * Warm the vector cache by preloading from SQLite.
     * Call after indexing completes so the first search is fast.
     */
    suspend fun warmCache(forceReload: Boolean = false) {
        val entries = withContext(Dispatchers.IO) {
            synchronized(cacheLock) {
                loadCacheLocked(forceReload = forceReload)
                cachedFloatOffsets?.size ?: 0
            }
        }
        Log.i(TAG, "vector cache warmed: $entries entries")
    }

    private fun loadCacheLocked(forceReload: Boolean = false) {
        val loadStarted = SystemClock.elapsedRealtimeNanos()
        val vectorFileLengthBytes = vectorStore.lengthBytes()
        val fingerprint = store.vectorCacheFingerprint(vectorFileLengthBytes)
        val hasCompletePayload = cachedFloatOffsets != null &&
            cachedDims != null &&
            cachedRecords != null &&
            cachedVectors != null
        if (!forceReload && VectorSearchEngine.isCacheFresh(cachedFingerprint, fingerprint, hasCompletePayload)) {
            return
        }

        val integrity = store.vectorIndexIntegrity(vectorFileLengthBytes)
        if (integrity.isCorrupt) {
            cachedFloatOffsets = null
            cachedDims = null
            cachedRecords = null
            cachedVectors = null
            cachedFingerprint = null
            error(
                "索引数据异常，请点击开始索引重建 " +
                    "(invalid=${integrity.invalidRecordCount}, duplicate=${integrity.duplicateOffsetRecordCount})"
            )
        }
        val verifiedFingerprint = VectorCacheFingerprint(
            indexedRecordCount = integrity.indexedVectorCount,
            vectorFileLengthBytes = vectorFileLengthBytes
        )

        val records = store.indexedByVectorOffset()
        val n = records.size
        val validMetadata = VectorSearchEngine.filterValidMetadata(records, vectorFileLengthBytes)
        if (validMetadata.skippedCount > 0) {
            Log.w(TAG, "filtered ${validMetadata.skippedCount}/$n records with invalid vector metadata")
        }
        if (validMetadata.records.isEmpty()) {
            cachedFloatOffsets = IntArray(0)
            cachedDims = IntArray(0)
            cachedRecords = emptyList()
            cachedVectors = FloatArray(0)
            cachedFingerprint = verifiedFingerprint
            Log.i(
                TAG,
                "cache load: SQLite ${elapsedMs(loadStarted).ms()}, valid records=0/$n fileBytes=$vectorFileLengthBytes"
            )
            return
        }

        // Commit the cache snapshot only after the vector file has been read successfully.
        val vectorLoadStarted = SystemClock.elapsedRealtimeNanos()
        val vectors = try {
            vectorStore.persistentMappedSession().let { session ->
                session.readAllVectors()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preload vectors: ${e.message}")
            // 重置所有缓存字段，保持一致性
            cachedFloatOffsets = null
            cachedDims = null
            cachedRecords = null
            cachedVectors = null
            cachedFingerprint = null
            throw e
        }

        // 全部成功后再赋值，确保原子性
        cachedFloatOffsets = validMetadata.floatOffsets
        cachedDims = validMetadata.dims
        cachedRecords = validMetadata.records
        cachedVectors = vectors
        cachedFingerprint = verifiedFingerprint
        val dim = validMetadata.dims.firstOrNull() ?: 512
        val vectorCount = if (dim > 0) vectors.size / dim else 0
        Log.i(
            TAG,
            "preloaded $vectorCount vectors (${vectors.size * 4 / 1024 / 1024}MB) " +
                "in ${elapsedMs(vectorLoadStarted).ms()}"
        )
        Log.i(
            TAG,
            "cache load: SQLite ${elapsedMs(loadStarted).ms()}, " +
                "valid records=${validMetadata.records.size}/$n fileBytes=$vectorFileLengthBytes"
        )
    }

    suspend fun search(
        model: ModelEntry,
        query: String,
        limit: Int,
        scoreThreshold: Float
    ): SearchResponse =
        withContext(Dispatchers.Default) {
            val totalStarted = SystemClock.elapsedRealtimeNanos()
            val config = requireNotNull(model.config)
            if (!model.textAvailable) {
                error("Missing text encoder/tokenizer: ${model.textMissingFiles.joinToString()}")
            }

            // Step 1: Text embedding (tokenize + inference)
            val sessionStarted = SystemClock.elapsedRealtimeNanos()
            var modelLoadMs = 0.0
            val textResult = sessionMutex.withLock {
                val session = sessionForLocked(model)
                modelLoadMs = elapsedMs(sessionStarted)
                session.embedWithTiming(query)
            }
            val queryEmbedding = textResult.embedding

            // Step 2: Load vector metadata and vector data as a consistent cache snapshot.
            val indexedStarted = SystemClock.elapsedRealtimeNanos()
            val (floatOffsets, dims, records, vectors) = synchronized(cacheLock) {
                loadCacheLocked()
                VectorCacheSnapshot(
                    floatOffsets = cachedFloatOffsets ?: IntArray(0),
                    dims = cachedDims ?: IntArray(0),
                    records = cachedRecords ?: emptyList(),
                    vectors = cachedVectors ?: FloatArray(0)
                )
            }
            val indexedReadMs = elapsedMs(indexedStarted)

            if (floatOffsets.isEmpty()) {
                val timing = SearchTiming(
                    totalMs = elapsedMs(totalStarted),
                    modelLoadMs = modelLoadMs,
                    textPrepareMs = textResult.prepareMs,
                    textInferenceMs = textResult.inferenceMs,
                    indexedReadMs = indexedReadMs,
                    comparedPhotos = 0,
                    resultCount = 0
                )
                Log.i(
                    TAG,
                    "search total=${timing.totalMs.ms()} " +
                        "model=${timing.modelLoadMs.ms()} textPrepare=${timing.textPrepareMs.ms()} " +
                        "textInference=${timing.textInferenceMs.ms()} indexedRead=${timing.indexedReadMs.ms()} " +
                        "compared=0 results=0"
                )
                return@withContext SearchResponse(results = emptyList(), timing = timing)
            }

            // Step 3: Parallel vector search with top-K selection (pure array dot product)
            val vectorSearchStarted = SystemClock.elapsedRealtimeNanos()
            val threshold = scoreThreshold.coerceAtLeast(0.0f)
            val resultLimit = limit.coerceAtLeast(0)

            val searchResult = parallelTopKSearch(
                vectors = vectors,
                vectorFloatOffsets = floatOffsets,
                dims = dims,
                queryEmbedding = queryEmbedding,
                threshold = threshold,
                topK = resultLimit.coerceAtLeast(1)
            )
            val vectorSearchMs = elapsedMs(vectorSearchStarted)

            // Step 4: Build results from top-K
            val buildStarted = SystemClock.elapsedRealtimeNanos()
            val resultCount = min(resultLimit, searchResult.size)
            val results = ArrayList<SearchResult>(resultCount)
            for (i in 0 until resultCount) {
                val idx = searchResult.indexes[i]
                results += SearchResult(records[idx], searchResult.scores[i])
            }
            val resultBuildMs = elapsedMs(buildStarted)

            val timing = SearchTiming(
                totalMs = elapsedMs(totalStarted),
                modelLoadMs = modelLoadMs,
                textPrepareMs = textResult.prepareMs,
                textInferenceMs = textResult.inferenceMs,
                indexedReadMs = indexedReadMs,
                vectorSearchMs = vectorSearchMs,
                resultBuildMs = resultBuildMs,
                comparedPhotos = searchResult.compared,
                resultCount = results.size
            )
            Log.i(
                TAG,
                "search total=${timing.totalMs.ms()} " +
                    "model=${timing.modelLoadMs.ms()} textPrepare=${timing.textPrepareMs.ms()} " +
                    "textInference=${timing.textInferenceMs.ms()} indexedRead=${timing.indexedReadMs.ms()} " +
                    "vectorSearch=${timing.vectorSearchMs.ms()} resultBuild=${timing.resultBuildMs.ms()} " +
                    "engine=${searchResult.engine} threshold=$threshold " +
                    "compared=${timing.comparedPhotos} results=${timing.resultCount}"
            )
            SearchResponse(results = results, timing = timing)
        }

    private data class VectorCacheSnapshot(
        val floatOffsets: IntArray,
        val dims: IntArray,
        val records: List<PhotoRecord>,
        val vectors: FloatArray
    )

    /**
     * Parallel top-K search: partition vectors across coroutines,
     * each computes local top-K, then merge.
     */
    private suspend fun parallelTopKSearch(
        vectors: FloatArray,
        vectorFloatOffsets: IntArray,
        dims: IntArray,
        queryEmbedding: FloatArray,
        threshold: Float,
        topK: Int
    ): VectorSearchEngine.TopKResult = coroutineScope {
        val n = vectorFloatOffsets.size
        val numThreads = SEARCH_PARALLELISM.coerceAtMost(n)

        NativeVectorSearch.searchTopK(
            vectors = vectors,
            vectorFloatOffsets = vectorFloatOffsets,
            dims = dims,
            queryEmbedding = queryEmbedding,
            threshold = threshold,
            topK = topK
        )?.let { return@coroutineScope it }

        if (n <= MIN_PARALLEL_SIZE || numThreads <= 1) {
            VectorSearchEngine.searchTopKRange(
                vectors = vectors,
                vectorFloatOffsets = vectorFloatOffsets,
                dims = dims,
                queryEmbedding = queryEmbedding,
                threshold = threshold,
                topK = topK,
                start = 0,
                count = n
            )
        } else {
            // Multi-threaded: partition and search in parallel
            val chunkSize = (n + numThreads - 1) / numThreads
            val jobs = (0 until numThreads).map { threadIdx ->
                async(Dispatchers.Default) {
                    val start = threadIdx * chunkSize
                    val end = min(start + chunkSize, n)
                    if (start >= end) return@async null
                    VectorSearchEngine.searchTopKRange(
                        vectors = vectors,
                        vectorFloatOffsets = vectorFloatOffsets,
                        dims = dims,
                        queryEmbedding = queryEmbedding,
                        threshold = threshold,
                        topK = topK,
                        start = start,
                        count = end - start
                    )
                }
            }
            val partialResults = jobs.awaitAll().filterNotNull()

            // Merge partial top-K results
            VectorSearchEngine.mergeTopK(partialResults, topK)
        }
    }

    suspend fun recent(limit: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            store.recent(limit).map { SearchResult(it, Float.NaN) }
        }

    fun page(results: List<SearchResult>, page: Int, pageSize: Int): List<SearchResult> {
        val from = page * pageSize
        if (from >= results.size) return emptyList()
        return results.subList(from, min(results.size, from + pageSize))
    }

    private fun sessionForLocked(model: ModelEntry): TextEmbeddingSession {
        val config = requireNotNull(model.config)
        val key = sessionKey(model)
        val existing = textSession
        if (existing != null && textSessionKey == key) return existing
        existing?.close()
        return TextEmbeddingSession(File(model.modelDirPath), config, TextBackend.CPU, store).also {
            textSession = it
            textSessionKey = key
        }
    }

    private fun sessionKey(model: ModelEntry): String {
        val config = requireNotNull(model.config)
        return "${model.modelDirPath}:${config.version}"
    }

    private fun elapsedMs(startedNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000.0

    private fun Double.ms(): String = "%.1fms".format(this)

    private companion object {
        const val TAG = "PhotoSearch"
        const val SEARCH_PARALLELISM = 4
        const val MIN_PARALLEL_SIZE = 4000
    }
}
