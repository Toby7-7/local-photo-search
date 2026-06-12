package com.photosearch.app.search

import android.content.Context
import android.util.Log
import com.photosearch.app.R
import com.photosearch.app.model.ModelEntry
import com.photosearch.app.model.ModelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class PhotoSearchUiState(
    val hasPermission: Boolean = false,
    val model: ModelEntry? = null,
    val stats: IndexStats = IndexStats(),
    val failedRecords: List<PhotoRecord> = emptyList(),
    val scoreThreshold: Float = 0.0f,
    val preloadSearchModel: Boolean = true,
    val preloadIndexModel: Boolean = true,
    val query: String = "",
    val visibleResults: List<SearchResult> = emptyList(),
    val allResults: List<SearchResult> = emptyList(),
    val filteredResults: List<SearchResult> = emptyList(),
    val searchTiming: SearchTiming? = null,
    val resultRevision: Long = 0L,
    val isSearching: Boolean = false,
    val searchMessage: String = "",
    val modelMessage: String = "checking model",
    val page: Int = 0
)

class PhotoSearchController(
    private val appContext: Context
) {
    private val preferences = appContext.getSharedPreferences("photo_search_settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scanner = MediaStoreScanner(appContext)
    private val registry = ModelRegistry(appContext)
    private val store = PhotoIndexStore(appContext)
    private val vectorStore = VectorStore(File(appContext.filesDir, "photo_vectors_f32.bin"))
    private val indexer = ImageEmbeddingIndexer(appContext, store, vectorStore)
    private val searcher = PhotoSearcher(store, vectorStore)
    private val speedLogDir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "logs")
    private val speedLogFile = File(speedLogDir, "index_speed_log.csv")
    private var indexJob: Job? = null
    private var searchJob: Job? = null
    private var searchPreloadJob: Job? = null
    private var indexPreloadJob: Job? = null
    private var backendChoice: BackendChoice? = null
    private val pageSize = 60

    private val _state = MutableStateFlow(
        PhotoSearchUiState(
            scoreThreshold = preferences.getFloat(KEY_SCORE_THRESHOLD, 0.0f).coerceIn(0.0f, MAX_SCORE_THRESHOLD),
            preloadSearchModel = preferences.getBoolean(KEY_PRELOAD_SEARCH_MODEL, true),
            preloadIndexModel = preferences.getBoolean(KEY_PRELOAD_INDEX_MODEL, true)
        )
    )
    val state: StateFlow<PhotoSearchUiState> = _state

    fun start() {
        refreshModelAndPermission()
        refreshStats()
        if (scanner.hasImagePermission()) {
            loadRecent()
        }
    }

    fun refreshModelAndPermission() {
        registry.ensureTemplateFolders()
        registry.copyModelAssets(appContext)
        val models = registry.scan()
        val model = models.firstOrNull { it.id == "mobileclip2-s0" } ?: models.firstOrNull()
        val message = when {
            model == null -> "No model configured"
            !model.available -> "Missing image model: ${model.missingFiles.joinToString()}"
            !model.textAvailable -> "Image indexing ready; search needs ${model.textMissingFiles.joinToString()}"
            else -> "${model.displayName} ready"
        }
        _state.update {
            it.copy(
                hasPermission = scanner.hasImagePermission(),
                model = model,
                modelMessage = message
            )
        }
        startConfiguredPreloads()
    }

    fun onPermissionResult() {
        refreshModelAndPermission()
        if (_state.value.hasPermission) scanAndIndex()
    }

    fun scanAndIndex() {
        startIndexing(forceScan = false)
    }

    private fun startIndexing(forceScan: Boolean) {
        val model = _state.value.model
        if (model == null) {
            Log.w(TAG, "startIndexing aborted: model not loaded")
            _state.update { it.copy(stats = it.stats.copy(message = appContext.getString(R.string.error_model_not_loaded))) }
            return
        }
        if (!scanner.hasImagePermission()) {
            Log.w(TAG, "startIndexing aborted: no photo permission")
            _state.update { it.copy(stats = it.stats.copy(message = appContext.getString(R.string.error_photo_permission_needed))) }
            return
        }
        if (!model.available) {
            Log.w(TAG, "startIndexing aborted: model not available, missing=${model.missingFiles}")
            _state.update { it.copy(stats = it.stats.copy(message = appContext.getString(R.string.error_model_files_missing, model.missingFiles.joinToString()))) }
            return
        }
        if (indexJob?.isActive == true) {
            Log.d(TAG, "startIndexing ignored: job already running")
            return
        }
        indexJob = scope.launch {
            try {
                val version = ImageEmbeddingIndexer.modelVersion(
                    requireNotNull(model.config).id,
                    model.config.version
                )
                resetSpeedLog()
                val integrity = withContext(Dispatchers.IO) {
                    store.vectorIndexIntegrity(vectorStore.lengthBytes())
                }
                val shouldRebuildCorruptIndex = integrity.isCorrupt
                if (shouldRebuildCorruptIndex) {
                    Log.w(TAG, "corrupt vector index detected: $integrity; rebuilding")
                    _state.update {
                        it.copy(
                            stats = it.stats.copy(
                                running = true,
                                message = appContext.getString(R.string.error_index_corrupt_rebuilding)
                            )
                        )
                    }
                    withContext(Dispatchers.IO) {
                        store.clearIndex()
                        vectorStore.clear()
                        searcher.invalidateCache()
                    }
                }
                val scanResult = prepareScan(
                    forceScan = forceScan || shouldRebuildCorruptIndex,
                    modelVersion = version
                )
                val statsAfterScan = withContext(Dispatchers.IO) { store.stats(scanResult.deleted) }
                if (statsAfterScan.pending == 0) {
                    _state.update {
                        it.copy(
                            stats = statsAfterScan.copy(
                                running = false,
                                message = "index up to date"
                            )
                        )
                    }
                    refreshCurrentResultsAfterIndexing()
                    return@launch
                }
                backendChoice = withContext(Dispatchers.Default) { indexer.chooseBackend(model) }
                val choice = requireNotNull(backendChoice)
                _state.update {
                    it.copy(
                        stats = statsAfterScan.copy(
                            running = true,
                            backendId = choice.backendId,
                            batchSize = choice.batchSize,
                            message = scanResult.message.ifBlank { choice.message }
                        )
                    )
                }
                indexer.indexPending(
                    model = model,
                    backendId = choice.backendId,
                    batchSize = choice.batchSize,
                    keepSession = _state.value.preloadIndexModel,
                    onStats = { stats ->
                        appendSpeedLog(stats.copy(message = "indexing", backendId = choice.backendId))
                        _state.update {
                            it.copy(stats = stats.copy(message = "indexing", backendId = choice.backendId))
                        }
                    }
                )
                searcher.warmCache(forceReload = true)
                _state.update {
                    it.copy(
                        stats = store.stats(scanResult.deleted).copy(
                            running = false,
                            backendId = choice.backendId,
                            batchSize = choice.batchSize,
                            message = "index up to date"
                        )
                    )
                }
                refreshCurrentResultsAfterIndexing()
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    _state.update {
                        it.copy(stats = it.stats.copy(running = false, paused = true, message = "paused"))
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        stats = store.stats().copy(
                            running = false,
                            message = (error.message ?: error::class.java.simpleName).take(120)
                        )
                    )
                }
            }
        }
    }

    fun pauseIndexing() {
        indexJob?.cancel()
        indexJob = null
        _state.update { it.copy(stats = it.stats.copy(running = false, paused = true, message = "paused")) }
    }

    fun rebuildIndex() {
        indexJob?.cancel()
        indexJob = null
        scope.launch(Dispatchers.IO) {
            store.clearIndex()
            vectorStore.clear()
            searcher.invalidateCache()
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        visibleResults = emptyList(),
                        allResults = emptyList(),
                        filteredResults = emptyList(),
                        searchTiming = null,
                        resultRevision = it.resultRevision + 1
                    )
                }
                startIndexing(forceScan = true)
            }
        }
    }

    fun retryFailed() {
        val model = _state.value.model ?: return
        if (!scanner.hasImagePermission() || !model.available) return
        if (indexJob?.isActive == true) return
        scope.launch(Dispatchers.IO) {
            val failed = store.allFailed()
            withContext(Dispatchers.Main) {
                indexSpecificRecords(model, failed)
            }
        }
    }

    fun retryFailed(mediaId: Long) {
        val model = _state.value.model ?: return
        if (!scanner.hasImagePermission() || !model.available) return
        if (indexJob?.isActive == true) return
        scope.launch(Dispatchers.IO) {
            val failed = store.failedRecord(mediaId)?.let(::listOf).orEmpty()
            withContext(Dispatchers.Main) {
                indexSpecificRecords(model, failed)
            }
        }
    }

    fun refreshFailedRecords() {
        scope.launch(Dispatchers.IO) {
            val failed = store.failed(100)
            withContext(Dispatchers.Main) {
                _state.update { it.copy(failedRecords = failed) }
            }
        }
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query, searchTiming = null) }
        searchJob?.cancel()
        searchJob = scope.launch {
            if (query.isBlank()) {
                loadRecentInternal()
            } else {
                runSearch(query)
            }
        }
    }

    fun updateScoreThreshold(threshold: Float) {
        val normalized = threshold.coerceIn(0.0f, MAX_SCORE_THRESHOLD)
        preferences.edit().putFloat(KEY_SCORE_THRESHOLD, normalized).apply()
        val query = _state.value.query
        _state.update { it.copy(scoreThreshold = normalized) }
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(150)
            runSearch(query)
        }
    }

    fun updatePreloadSearchModel(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_PRELOAD_SEARCH_MODEL, enabled).apply()
        _state.update { it.copy(preloadSearchModel = enabled) }
        if (enabled) {
            startSearchModelPreload()
        } else {
            searchPreloadJob?.cancel()
            searchPreloadJob = scope.launch { searcher.release() }
        }
    }

    fun updatePreloadIndexModel(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_PRELOAD_INDEX_MODEL, enabled).apply()
        _state.update { it.copy(preloadIndexModel = enabled) }
        if (enabled) {
            startIndexModelPreload()
        } else {
            indexPreloadJob?.cancel()
            indexPreloadJob = scope.launch { indexer.releaseCachedSession() }
        }
    }

    fun loadNextPage() {
        val current = _state.value
        val nextPage = current.page + 1
        val visible = searcher.page(current.filteredResults, 0, (nextPage + 1) * pageSize)
        _state.update { it.copy(page = nextPage, visibleResults = visible) }
    }

    fun refreshStats() {
        val stats = store.stats()
        _state.update { it.copy(stats = stats) }
        if (stats.failed > 0) {
            refreshFailedRecords()
        } else if (_state.value.failedRecords.isNotEmpty()) {
            _state.update { it.copy(failedRecords = emptyList()) }
        }
    }

    private fun startConfiguredPreloads() {
        if (_state.value.preloadSearchModel) startSearchModelPreload()
        if (_state.value.preloadIndexModel) startIndexModelPreload()
    }

    private fun startSearchModelPreload() {
        val model = _state.value.model ?: return
        if (!model.textAvailable) return
        if (searchPreloadJob?.isActive == true) return
        searchPreloadJob = scope.launch {
            runCatching {
                searcher.preload(model)
                // Warm vector cache after model load to avoid 2-second delay on first search
                searcher.warmCache()
            }
                .onFailure { error -> Log.w(TAG, "preload search model failed: ${error.message}") }
        }
    }

    private fun startIndexModelPreload() {
        val model = _state.value.model ?: return
        if (!model.available) return
        if (indexPreloadJob?.isActive == true) return
        indexPreloadJob = scope.launch {
            runCatching {
                val choice = backendChoice ?: withContext(Dispatchers.Default) { indexer.chooseBackend(model) }
                    .also { backendChoice = it }
                indexer.preload(model, choice.backendId)
            }.onFailure { error ->
                Log.w(TAG, "preload index model failed: ${error.message}")
            }
        }
    }

    private suspend fun prepareScan(forceScan: Boolean, modelVersion: String): ScanResult =
        withContext(Dispatchers.IO) {
            val hasPhotos = store.hasAnyPhotos()
            val savedVersion = preferences.getString(KEY_LAST_SCAN_MODEL_VERSION, null)
            val savedDateModified = preferences.getLong(KEY_LAST_SCAN_DATE_MODIFIED, -1L)
            val savedMediaId = preferences.getLong(KEY_LAST_SCAN_MEDIA_ID, -1L)
            val needsFullScan = forceScan ||
                !hasPhotos ||
                savedVersion != modelVersion ||
                savedDateModified < 0L ||
                savedMediaId < 0L
            if (needsFullScan) {
                _state.update { it.copy(stats = it.stats.copy(running = true, message = "scanning photos")) }
                val images = scanner.scanImages()
                val deleted = store.syncScanned(images, modelVersion)
                searcher.invalidateVectorCache()
                persistLatestScanAnchor(images.firstOrNull(), modelVersion)
                Log.i(TAG, "full scan images=${images.size} deleted=$deleted model=$modelVersion")
                return@withContext ScanResult(
                    deleted = deleted,
                    message = if (images.isEmpty()) "no photos found" else "scanned ${images.size} photos"
                )
            }

            // Incremental scan: compare total counts to detect additions/deletions.
            val devicePhotoCount = scanner.countImages()
            val dbPhotoCount = store.countTotalPhotos()
            Log.i(TAG, "incremental scan count check: device=$devicePhotoCount db=$dbPhotoCount")

            if (devicePhotoCount != dbPhotoCount) {
                // Count mismatch, possible additions or deletions; run full scan
                _state.update { it.copy(stats = store.stats().copy(running = true, message = "scanning photos")) }
                val images = scanner.scanImages()
                val deleted = store.syncScanned(images, modelVersion)
                searcher.invalidateVectorCache()
                persistLatestScanAnchor(images.firstOrNull(), modelVersion)
                Log.i(TAG, "count mismatch ($devicePhotoCount vs $dbPhotoCount), full scan images=${images.size} deleted=$deleted")
                return@withContext ScanResult(
                    deleted = deleted,
                    message = "scanned ${images.size} photos"
                )
            }

            // Count matches; check if latest photo changed
            _state.update { it.copy(stats = store.stats().copy(running = true, message = "checking new photos")) }
            val latest = scanner.latestImage()
            if (latest == null) {
                Log.i(TAG, "incremental scan found no visible photos")
                return@withContext ScanResult(deleted = 0, message = "no visible photos")
            }
            val unchanged = latest.dateModified == savedDateModified && latest.mediaId == savedMediaId
            if (unchanged) {
                Log.i(TAG, "incremental scan skipped latest=${latest.mediaId}/${latest.dateModified}")
                return@withContext ScanResult(deleted = 0, message = "no new photos")
            }

            // Latest photo changed but total count unchanged (possible replacements); incremental scan
            _state.update { it.copy(stats = store.stats().copy(running = true, message = "scanning new photos")) }
            val newImages = scanner.scanImagesNewerThan(savedDateModified, savedMediaId)
            val changed = store.upsertScanned(newImages, modelVersion)
            if (changed > 0) searcher.invalidateVectorCache()
            persistLatestScanAnchor(latest, modelVersion)
            Log.i(
                TAG,
                "incremental scan latest=${latest.mediaId}/${latest.dateModified} " +
                    "previous=$savedMediaId/$savedDateModified new=${newImages.size} changed=$changed"
            )
            ScanResult(
                deleted = 0,
                message = if (changed == 0) "no index changes" else "added $changed photos"
            )
        }

    private fun persistLatestScanAnchor(latest: MediaImage?, modelVersion: String) {
        if (latest == null) return
        preferences.edit()
            .putLong(KEY_LAST_SCAN_DATE_MODIFIED, latest.dateModified)
            .putLong(KEY_LAST_SCAN_MEDIA_ID, latest.mediaId)
            .putString(KEY_LAST_SCAN_MODEL_VERSION, modelVersion)
            .apply()
    }

    private fun indexSpecificRecords(model: ModelEntry, records: List<PhotoRecord>) {
        if (records.isEmpty() || indexJob?.isActive == true) {
            refreshFailedRecords()
            refreshStats()
            return
        }
        indexJob = scope.launch {
            try {
                val choice = backendChoice ?: withContext(Dispatchers.Default) { indexer.chooseBackend(model) }
                    .also { backendChoice = it }
                _state.update {
                    it.copy(
                        stats = store.stats().copy(
                            running = true,
                            backendId = choice.backendId,
                            batchSize = records.size.coerceAtLeast(1),
                            message = "retrying failed photos"
                        )
                    )
                }
                indexer.indexRecords(
                    model = model,
                    backendId = choice.backendId,
                    records = records,
                    batchSize = choice.batchSize,
                    keepSession = _state.value.preloadIndexModel,
                    onStats = { stats ->
                        appendSpeedLog(stats.copy(message = "retrying", backendId = choice.backendId))
                        _state.update {
                            it.copy(stats = stats.copy(message = "retrying", backendId = choice.backendId))
                        }
                    }
                )
                searcher.warmCache(forceReload = true)
                _state.update {
                    it.copy(
                        stats = store.stats().copy(
                            running = false,
                            backendId = choice.backendId,
                            batchSize = choice.batchSize,
                            message = "retry complete"
                        )
                    )
                }
                refreshFailedRecords()
                refreshCurrentResultsAfterIndexing()
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    _state.update {
                        it.copy(stats = it.stats.copy(running = false, paused = true, message = "paused"))
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        stats = store.stats().copy(
                            running = false,
                            message = (error.message ?: error::class.java.simpleName).take(120)
                        )
                    )
                }
                refreshFailedRecords()
            }
        }
    }

    private fun refreshCurrentResultsAfterIndexing() {
        val query = _state.value.query
        if (query.isBlank()) {
            loadRecent()
        } else {
            searchJob?.cancel()
            searchJob = scope.launch {
                runSearch(query)
            }
        }
    }

    private fun loadRecent() {
        searchJob?.cancel()
        searchJob = scope.launch {
            loadRecentInternal()
        }
    }

    private suspend fun loadRecentInternal() {
        val results = searcher.recent(600)
        _state.update {
            it.copy(
                allResults = results,
                filteredResults = results,
                visibleResults = searcher.page(results, 0, pageSize),
                page = 0,
                isSearching = false,
                searchTiming = null,
                searchMessage = if (results.isEmpty()) appContext.getString(R.string.no_indexed_photos) else "",
                resultRevision = it.resultRevision + 1
            )
        }
    }

    private suspend fun runSearch(query: String) {
        val model = _state.value.model
        if (model == null) {
            _state.update { it.copy(searchMessage = appContext.getString(R.string.no_model_configured)) }
            return
        }
        _state.update { it.copy(isSearching = true, searchTiming = null, searchMessage = appContext.getString(R.string.searching)) }
        runCatching {
            searcher.search(
                model = model,
                query = query,
                limit = SEARCH_RESULT_LIMIT,
                scoreThreshold = _state.value.scoreThreshold
            )
        }.fold(
            onSuccess = { response ->
                _state.update {
                    it.copy(
                        allResults = response.results,
                        filteredResults = response.results,
                        visibleResults = searcher.page(response.results, 0, pageSize),
                        page = 0,
                        isSearching = false,
                        searchTiming = response.timing.copy(resultCount = response.results.size),
                        searchMessage = searchMessage(query, response.results),
                        resultRevision = it.resultRevision + 1
                    )
                }
            },
            onFailure = { error ->
                if (error is CancellationException) {
                    return@fold
                }
                _state.update {
                    it.copy(
                        isSearching = false,
                        searchTiming = null,
                        searchMessage = error.message ?: error::class.java.simpleName
                    )
                }
            }
        )
    }

    private fun resetSpeedLog() {
        runCatching {
            speedLogDir.mkdirs()
            speedLogFile.writeText(
                "timestamp_ms,indexed,pending,failed,batch_size,e2e_ips,infer_ips,batch_ms,work_ms,wall_ms,infer_ms,prep_ms,decode_ms,tensor_ms,write_ms,backend,status\n"
            )
        }
    }

    private fun appendSpeedLog(stats: IndexStats) {
        runCatching {
            speedLogDir.mkdirs()
            speedLogFile.appendText(
                buildString {
                    append(System.currentTimeMillis())
                    append(',')
                    append(stats.indexed)
                    append(',')
                    append(stats.pending)
                    append(',')
                    append(stats.failed)
                    append(',')
                    append(stats.batchSize)
                    append(',')
                    append(stats.recentImagesPerSecond.csv())
                    append(',')
                    append(stats.recentInferenceImagesPerSecond.csv())
                    append(',')
                    append(stats.recentBatchMs.csv())
                    append(',')
                    append(stats.recentWorkMs.csv())
                    append(',')
                    append(stats.recentWallMs.csv())
                    append(',')
                    append(stats.recentInferenceMs.csv())
                    append(',')
                    append(stats.recentPreprocessMs.csv())
                    append(',')
                    append(stats.recentDecodeMs.csv())
                    append(',')
                    append(stats.recentTensorMs.csv())
                    append(',')
                    append(stats.recentWriteMs.csv())
                    append(',')
                    append(stats.backendId)
                    append(',')
                    append(stats.message)
                    append('\n')
                }
            )
        }
    }

    private fun Double?.csv(): String =
        this?.let { String.format(Locale.US, "%.3f", it) } ?: ""

    private fun searchMessage(query: String, results: List<SearchResult>): String =
        when {
            query.isBlank() -> ""
            results.isEmpty() -> appContext.getString(R.string.no_results)
            else -> appContext.getString(R.string.search_results_count, results.size)
        }

    private companion object {
        const val KEY_SCORE_THRESHOLD = "score_threshold"
        const val KEY_PRELOAD_SEARCH_MODEL = "preload_search_model"
        const val KEY_PRELOAD_INDEX_MODEL = "preload_index_model"
        const val KEY_LAST_SCAN_DATE_MODIFIED = "last_scan_date_modified"
        const val KEY_LAST_SCAN_MEDIA_ID = "last_scan_media_id"
        const val KEY_LAST_SCAN_MODEL_VERSION = "last_scan_model_version"
        const val MAX_SCORE_THRESHOLD = 0.5f
        const val TAG = "PhotoSearch"
        // Cap search results to enable efficient top-K heap selection instead of full sort.
        // 2000 results = ~33 pages at 60/page. Adjust threshold to filter further.
        const val SEARCH_RESULT_LIMIT = 2000
    }
}

private data class ScanResult(
    val deleted: Int,
    val message: String
)
