package com.photosearch.app.search

import android.net.Uri

enum class IndexStatus(val dbValue: String) {
    Pending("pending"),
    Indexed("indexed"),
    Failed("failed")
}

data class MediaImage(
    val mediaId: Long,
    val uri: Uri,
    val displayName: String?,
    val mimeType: String?,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAdded: Long,
    val dateModified: Long
)

data class PhotoRecord(
    val mediaId: Long,
    val uri: String,
    val displayName: String?,
    val mimeType: String?,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val status: IndexStatus,
    val vectorOffset: Long,
    val vectorDim: Int,
    val indexedAt: Long,
    val modelVersion: String?,
    val error: String?,
    val retryCount: Int
) {
    val parsedUri: Uri
        get() = Uri.parse(uri)
}

data class IndexStats(
    val total: Int = 0,
    val indexed: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val deletedLastScan: Int = 0,
    val running: Boolean = false,
    val paused: Boolean = false,
    val backendId: String = "-",
    val batchSize: Int = 1,
    val recentImagesPerSecond: Double? = null,
    val recentInferenceImagesPerSecond: Double? = null,
    val recentBatchMs: Double? = null,
    val recentWorkMs: Double? = null,
    val recentWallMs: Double? = null,
    val recentInferenceMs: Double? = null,
    val recentPreprocessMs: Double? = null,
    val recentDecodeMs: Double? = null,
    val recentTensorMs: Double? = null,
    val recentWriteMs: Double? = null,
    val message: String = "idle"
)

data class SearchResult(
    val record: PhotoRecord,
    val score: Float
)

data class SearchTiming(
    val totalMs: Double = 0.0,
    val modelLoadMs: Double = 0.0,
    val textPrepareMs: Double = 0.0,
    val textInferenceMs: Double = 0.0,
    val indexedReadMs: Double = 0.0,
    val vectorSearchMs: Double = 0.0,
    val resultBuildMs: Double = 0.0,
    val comparedPhotos: Int = 0,
    val resultCount: Int = 0
)

data class SearchResponse(
    val results: List<SearchResult>,
    val timing: SearchTiming
)

data class VectorIndexIntegrity(
    val indexedVectorCount: Int,
    val invalidRecordCount: Int,
    val duplicateOffsetGroupCount: Int,
    val duplicateOffsetRecordCount: Int,
    val vectorFileLengthBytes: Long
) {
    val isCorrupt: Boolean
        get() = invalidRecordCount > 0 || duplicateOffsetRecordCount > 0
}

data class BackendChoice(
    val backendId: String,
    val backendName: String,
    val batchSize: Int,
    val inferenceImagesPerSecond: Double? = null,
    val message: String,
    val probeResults: List<BatchProbeResult> = emptyList()
)

data class BatchProbeResult(
    val batchSize: Int,
    val inferenceImagesPerSecond: Double,
    val inferenceMs: Double
)
