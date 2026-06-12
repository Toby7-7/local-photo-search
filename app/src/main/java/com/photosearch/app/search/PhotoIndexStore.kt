package com.photosearch.app.search

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class PhotoIndexStore(context: Context) : SQLiteOpenHelper(
    context,
    "photo_search_index.db",
    null,
    2
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE photos (
                media_id INTEGER PRIMARY KEY,
                uri TEXT NOT NULL,
                display_name TEXT,
                mime_type TEXT,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                date_added INTEGER NOT NULL DEFAULT 0,
                date_modified INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                vector_offset INTEGER NOT NULL DEFAULT -1,
                vector_dim INTEGER NOT NULL DEFAULT 0,
                indexed_at INTEGER NOT NULL DEFAULT 0,
                model_version TEXT,
                error TEXT,
                retry_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_photos_status ON photos(status)")
        db.execSQL("CREATE INDEX idx_photos_modified ON photos(date_modified)")
        db.execSQL("CREATE INDEX idx_photos_status_modified ON photos(status, date_modified DESC, media_id DESC)")
        db.execSQL("CREATE INDEX idx_photos_status_vector_offset ON photos(status, vector_offset ASC)")

        // 文本查询向量缓存表（持久化 LRU 缓存）
        db.execSQL(
            """
            CREATE TABLE query_embedding_cache (
                query_text TEXT PRIMARY KEY,
                model_key TEXT NOT NULL DEFAULT '',
                embedding BLOB NOT NULL,
                created_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 -> v2: 添加查询向量缓存表
            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS query_embedding_cache (
                query_text TEXT PRIMARY KEY,
                model_key TEXT NOT NULL DEFAULT '',
                embedding BLOB NOT NULL,
                created_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
            )
            ensureQueryCacheModelKeyColumn(db)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_status_modified ON photos(status, date_modified DESC, media_id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_status_vector_offset ON photos(status, vector_offset ASC)")
        ensureQueryCacheModelKeyColumn(db)
    }

    fun syncScanned(images: List<MediaImage>, modelVersion: String): Int {
        val db = writableDatabase
        val seen = images.mapTo(HashSet()) { it.mediaId }
        db.beginTransaction()
        try {
            val existingById = existingIndexLocked(db)
            images.forEach { image ->
                val existing = existingById[image.mediaId]
                val status = when {
                    existing == null -> IndexStatus.Pending
                    existing.dateModified != image.dateModified -> IndexStatus.Pending
                    existing.sizeBytes != image.sizeBytes -> IndexStatus.Pending
                    existing.modelVersion != modelVersion -> IndexStatus.Pending
                    else -> existing.status
                }
                if (existing != null &&
                    status == existing.status &&
                    existing.dateModified == image.dateModified &&
                    existing.sizeBytes == image.sizeBytes &&
                    existing.modelVersion == modelVersion
                ) {
                    return@forEach
                }
                val values = ContentValues().apply {
                    put("media_id", image.mediaId)
                    put("uri", image.uri.toString())
                    put("display_name", image.displayName)
                    put("mime_type", image.mimeType)
                    put("width", image.width)
                    put("height", image.height)
                    put("size_bytes", image.sizeBytes)
                    put("date_added", image.dateAdded)
                    put("date_modified", image.dateModified)
                    put("status", status.dbValue)
                    put("vector_offset", if (status == IndexStatus.Pending) -1L else existing?.vectorOffset ?: -1L)
                    put("vector_dim", if (status == IndexStatus.Pending) 0 else existing?.vectorDim ?: 0)
                    put("indexed_at", if (status == IndexStatus.Pending) 0L else existing?.indexedAt ?: 0L)
                    put("model_version", if (status == IndexStatus.Pending) modelVersion else existing?.modelVersion)
                    put("error", if (status == IndexStatus.Pending) null else existing?.error)
                    put("retry_count", if (status == IndexStatus.Pending) 0 else existing?.retryCount ?: 0)
                }
                db.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }

            val deleted = deleteMissingLocked(db, existingById.keys, seen)
            db.setTransactionSuccessful()
            return deleted
        } finally {
            db.endTransaction()
        }
    }

    fun upsertScanned(images: List<MediaImage>, modelVersion: String): Int {
        if (images.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existingById = existingIndexLocked(db, images.map { it.mediaId }.toSet())
            var changed = 0
            images.forEach { image ->
                val existing = existingById[image.mediaId]
                val status = when {
                    existing == null -> IndexStatus.Pending
                    existing.dateModified != image.dateModified -> IndexStatus.Pending
                    existing.sizeBytes != image.sizeBytes -> IndexStatus.Pending
                    existing.modelVersion != modelVersion -> IndexStatus.Pending
                    else -> existing.status
                }
                if (existing != null &&
                    status == existing.status &&
                    existing.dateModified == image.dateModified &&
                    existing.sizeBytes == image.sizeBytes &&
                    existing.modelVersion == modelVersion
                ) {
                    return@forEach
                }
                val values = ContentValues().apply {
                    put("media_id", image.mediaId)
                    put("uri", image.uri.toString())
                    put("display_name", image.displayName)
                    put("mime_type", image.mimeType)
                    put("width", image.width)
                    put("height", image.height)
                    put("size_bytes", image.sizeBytes)
                    put("date_added", image.dateAdded)
                    put("date_modified", image.dateModified)
                    put("status", status.dbValue)
                    put("vector_offset", if (status == IndexStatus.Pending) -1L else existing?.vectorOffset ?: -1L)
                    put("vector_dim", if (status == IndexStatus.Pending) 0 else existing?.vectorDim ?: 0)
                    put("indexed_at", if (status == IndexStatus.Pending) 0L else existing?.indexedAt ?: 0L)
                    put("model_version", if (status == IndexStatus.Pending) modelVersion else existing?.modelVersion)
                    put("error", if (status == IndexStatus.Pending) null else existing?.error)
                    put("retry_count", if (status == IndexStatus.Pending) 0 else existing?.retryCount ?: 0)
                }
                db.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                changed += 1
            }
            db.setTransactionSuccessful()
            return changed
        } finally {
            db.endTransaction()
        }
    }

    fun pending(limit: Int): List<PhotoRecord> =
        readableDatabase.query(
            "photos",
            PHOTO_RECORD_COLUMNS,
            "status = ?",
            arrayOf(IndexStatus.Pending.dbValue),
            null,
            null,
            "date_modified DESC, media_id DESC",
            limit.toString()
        ).use { cursor -> cursor.toRecords() }

    fun indexedByVectorOffset(): List<PhotoRecord> =
        readableDatabase.query(
            "photos",
            PHOTO_RECORD_COLUMNS,
            "status = ? AND vector_offset >= 0",
            arrayOf(IndexStatus.Indexed.dbValue),
            null,
            null,
            "vector_offset ASC"
        ).use { cursor -> cursor.toRecords() }

    fun recent(limit: Int): List<PhotoRecord> =
        readableDatabase.query(
            "photos",
            PHOTO_RECORD_COLUMNS,
            "status = ?",
            arrayOf(IndexStatus.Indexed.dbValue),
            null,
            null,
            "date_modified DESC, media_id DESC",
            limit.toString()
        ).use { cursor -> cursor.toRecords() }

    fun failed(limit: Int): List<PhotoRecord> =
        readableDatabase.query(
            "photos",
            PHOTO_RECORD_COLUMNS,
            "status = ?",
            arrayOf(IndexStatus.Failed.dbValue),
            null,
            null,
            "retry_count DESC, date_modified DESC, media_id DESC",
            limit.toString()
        ).use { cursor -> cursor.toRecords() }

    fun allFailed(): List<PhotoRecord> =
        readableDatabase.query(
            "photos",
            PHOTO_RECORD_COLUMNS,
            "status = ?",
            arrayOf(IndexStatus.Failed.dbValue),
            null,
            null,
            "retry_count DESC, date_modified DESC, media_id DESC"
        ).use { cursor -> cursor.toRecords() }

    fun failedRecord(mediaId: Long): PhotoRecord? =
        readableDatabase.query(
            "photos",
            PHOTO_RECORD_COLUMNS,
            "media_id = ? AND status = ?",
            arrayOf(mediaId.toString(), IndexStatus.Failed.dbValue),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRecord() else null
        }

    fun markIndexedBatch(items: List<IndexedUpdate>, modelVersion: String) {
        if (items.isEmpty()) return
        val db = writableDatabase
        val indexedAt = System.currentTimeMillis()
        db.beginTransaction()
        try {
            items.forEach { item ->
                val values = ContentValues().apply {
                    put("status", IndexStatus.Indexed.dbValue)
                    put("vector_offset", item.vectorOffset)
                    put("vector_dim", item.vectorDim)
                    put("indexed_at", indexedAt)
                    put("model_version", modelVersion)
                    putNull("error")
                }
                db.update("photos", values, "media_id = ?", arrayOf(item.mediaId.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markFailed(mediaId: Long, error: String) {
        val current = getRecord(mediaId)
        val values = ContentValues().apply {
            put("status", IndexStatus.Failed.dbValue)
            put("error", error.take(400))
            put("retry_count", (current?.retryCount ?: 0) + 1)
        }
        writableDatabase.update("photos", values, "media_id = ?", arrayOf(mediaId.toString()))
    }

    fun resetFailed() {
        val values = ContentValues().apply {
            put("status", IndexStatus.Pending.dbValue)
            putNull("error")
        }
        writableDatabase.update("photos", values, "status = ?", arrayOf(IndexStatus.Failed.dbValue))
    }

    fun resetFailed(mediaId: Long) {
        val values = ContentValues().apply {
            put("status", IndexStatus.Pending.dbValue)
            putNull("error")
        }
        writableDatabase.update(
            "photos",
            values,
            "media_id = ? AND status = ?",
            arrayOf(mediaId.toString(), IndexStatus.Failed.dbValue)
        )
    }

    fun clearIndex() {
        writableDatabase.delete("photos", null, null)
        writableDatabase.delete("query_embedding_cache", null, null)
    }

    /**
     * 清除文本嵌入 SQLite 持久化缓存（不删除索引数据）
     * 调用场景：模型变化、索引重建后，防止过期嵌入被重新加载
     */
    fun clearTextEmbeddingCache() {
        writableDatabase.delete("query_embedding_cache", null, null)
        Log.i(TAG, "SQLite text embedding cache cleared")
    }

    // --- 查询向量缓存（持久化 LRU） ---

    fun putCachedEmbedding(modelKey: String, query: String, embedding: FloatArray) {
        val db = writableDatabase
        val bytes = ByteArray(embedding.size * 4)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(embedding)
        val values = ContentValues().apply {
            put("query_text", query)
            put("model_key", modelKey)
            put("embedding", bytes)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("query_embedding_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        // LRU eviction: only scan/sort/delete when actually over the cap, instead of
        // on every insert (the prior DELETE ran its subquery sort even for <100 rows).
        val count = db.rawQuery("SELECT COUNT(*) FROM query_embedding_cache", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        if (count > QUERY_CACHE_LIMIT) {
            db.execSQL(
                "DELETE FROM query_embedding_cache WHERE rowid NOT IN " +
                    "(SELECT rowid FROM query_embedding_cache ORDER BY created_at DESC LIMIT $QUERY_CACHE_LIMIT)"
            )
        }
    }

    fun loadAllCachedEmbeddings(modelKey: String): Map<String, FloatArray> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT query_text, embedding FROM query_embedding_cache WHERE model_key = ? ORDER BY created_at DESC LIMIT 100",
            arrayOf(modelKey)
        )
        val result = mutableMapOf<String, FloatArray>()
        cursor.use {
            while (it.moveToNext()) {
                val query = it.getString(0)
                val bytes = it.getBlob(1)
                if (bytes.size % 4 != 0) {
                    Log.w(TAG, "Invalid cached embedding size ${bytes.size}, skipping")
                    continue
                }
                val floats = FloatArray(bytes.size / 4)
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                result[query] = floats
            }
        }
        return result
    }

    fun stats(deletedLastScan: Int = 0): IndexStats {
        val db = readableDatabase
        var total = 0
        var indexed = 0
        var pending = 0
        var failed = 0
        db.rawQuery("SELECT status, COUNT(*) FROM photos GROUP BY status", null).use { cursor ->
            while (cursor.moveToNext()) {
                val status = cursor.getString(0)
                val count = cursor.getInt(1)
                total += count
                when (status) {
                    IndexStatus.Indexed.dbValue -> indexed = count
                    IndexStatus.Pending.dbValue -> pending = count
                    IndexStatus.Failed.dbValue -> failed = count
                }
            }
        }
        return IndexStats(
            total = total,
            indexed = indexed,
            pending = pending,
            failed = failed,
            deletedLastScan = deletedLastScan
        )
    }

    fun hasAnyPhotos(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM photos LIMIT 1", null).use { cursor ->
            cursor.moveToFirst()
        }

    fun countTotalPhotos(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM photos", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    internal fun vectorCacheFingerprint(vectorFileLengthBytes: Long): VectorCacheFingerprint {
        val indexedVectorCount = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM photos WHERE status = ? AND vector_offset >= 0",
            arrayOf(IndexStatus.Indexed.dbValue)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        return VectorCacheFingerprint(
            indexedRecordCount = indexedVectorCount,
            vectorFileLengthBytes = vectorFileLengthBytes
        )
    }

    fun vectorIndexIntegrity(vectorFileLengthBytes: Long): VectorIndexIntegrity {
        val db = readableDatabase
        val indexedVectorCount = db.rawQuery(
            "SELECT COUNT(*) FROM photos WHERE status = ? AND vector_offset >= 0",
            arrayOf(IndexStatus.Indexed.dbValue)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        val invalidRecordCount = db.rawQuery(
            """
            SELECT COUNT(*) FROM photos
            WHERE status = ?
              AND (
                vector_offset < 0
                OR vector_dim <= 0
                OR vector_offset % ? != 0
                OR vector_offset + vector_dim * ? > ?
              )
            """.trimIndent(),
            arrayOf(
                IndexStatus.Indexed.dbValue,
                Float.SIZE_BYTES.toString(),
                Float.SIZE_BYTES.toString(),
                vectorFileLengthBytes.toString()
            )
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        val duplicate = db.rawQuery(
            """
            SELECT COUNT(*), COALESCE(SUM(offset_count), 0)
            FROM (
                SELECT vector_offset, COUNT(*) AS offset_count
                FROM photos
                WHERE status = ? AND vector_offset >= 0
                GROUP BY vector_offset
                HAVING offset_count > 1
            )
            """.trimIndent(),
            arrayOf(IndexStatus.Indexed.dbValue)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getInt(0) to cursor.getInt(1)
            } else {
                0 to 0
            }
        }
        return VectorIndexIntegrity(
            indexedVectorCount = indexedVectorCount,
            invalidRecordCount = invalidRecordCount,
            duplicateOffsetGroupCount = duplicate.first,
            duplicateOffsetRecordCount = duplicate.second,
            vectorFileLengthBytes = vectorFileLengthBytes
        )
    }

    private fun getRecord(mediaId: Long): PhotoRecord? =
        readableDatabase.query(
            "photos",
            PHOTO_RECORD_COLUMNS,
            "media_id = ?",
            arrayOf(mediaId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRecord() else null
        }

    private fun existingIndexLocked(db: SQLiteDatabase): Map<Long, ExistingPhotoIndex> =
        db.query(
            "photos",
            arrayOf(
                "media_id",
                "date_modified",
                "size_bytes",
                "status",
                "vector_offset",
                "vector_dim",
                "indexed_at",
                "model_version",
                "error",
                "retry_count"
            ),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            val records = HashMap<Long, ExistingPhotoIndex>(cursor.count)
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(cursor.column("media_id"))
                records[mediaId] = ExistingPhotoIndex(
                    dateModified = cursor.getLong(cursor.column("date_modified")),
                    sizeBytes = cursor.getLong(cursor.column("size_bytes")),
                    status = statusFromDb(cursor.getString(cursor.column("status"))),
                    vectorOffset = cursor.getLong(cursor.column("vector_offset")),
                    vectorDim = cursor.getInt(cursor.column("vector_dim")),
                    indexedAt = cursor.getLong(cursor.column("indexed_at")),
                    modelVersion = cursor.getNullableString(cursor.column("model_version")),
                    error = cursor.getNullableString(cursor.column("error")),
                    retryCount = cursor.getInt(cursor.column("retry_count"))
                )
            }
            records
        }

    private fun existingIndexLocked(db: SQLiteDatabase, mediaIds: Set<Long>): Map<Long, ExistingPhotoIndex> {
        if (mediaIds.isEmpty()) return emptyMap()
        val records = HashMap<Long, ExistingPhotoIndex>(mediaIds.size)
        mediaIds.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.query(
                "photos",
                arrayOf(
                    "media_id",
                    "date_modified",
                    "size_bytes",
                    "status",
                    "vector_offset",
                    "vector_dim",
                    "indexed_at",
                    "model_version",
                    "error",
                    "retry_count"
                ),
                "media_id IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
                null,
                null,
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(cursor.column("media_id"))
                    records[mediaId] = ExistingPhotoIndex(
                        dateModified = cursor.getLong(cursor.column("date_modified")),
                        sizeBytes = cursor.getLong(cursor.column("size_bytes")),
                        status = statusFromDb(cursor.getString(cursor.column("status"))),
                        vectorOffset = cursor.getLong(cursor.column("vector_offset")),
                        vectorDim = cursor.getInt(cursor.column("vector_dim")),
                        indexedAt = cursor.getLong(cursor.column("indexed_at")),
                        modelVersion = cursor.getNullableString(cursor.column("model_version")),
                        error = cursor.getNullableString(cursor.column("error")),
                        retryCount = cursor.getInt(cursor.column("retry_count"))
                    )
                }
            }
        }
        return records
    }

    private fun deleteMissingLocked(db: SQLiteDatabase, existing: Set<Long>, seen: Set<Long>): Int {
        var deleted = 0
        existing.filterNot { it in seen }.forEach { id ->
            deleted += db.delete("photos", "media_id = ?", arrayOf(id.toString()))
        }
        return deleted
    }

    private fun Cursor.toRecords(): List<PhotoRecord> {
        val columns = photoRecordColumns()
        val records = ArrayList<PhotoRecord>()
        while (moveToNext()) records += toRecord(columns)
        return records
    }

    private fun Cursor.toRecord(): PhotoRecord =
        toRecord(photoRecordColumns())

    private fun Cursor.toRecord(columns: PhotoRecordColumns): PhotoRecord =
        PhotoRecord(
            mediaId = getLong(columns.mediaId),
            uri = getString(columns.uri),
            displayName = getNullableString(columns.displayName),
            mimeType = getNullableString(columns.mimeType),
            width = getInt(columns.width),
            height = getInt(columns.height),
            sizeBytes = getLong(columns.sizeBytes),
            dateAdded = getLong(columns.dateAdded),
            dateModified = getLong(columns.dateModified),
            status = statusFromDb(getString(columns.status)),
            vectorOffset = getLong(columns.vectorOffset),
            vectorDim = getInt(columns.vectorDim),
            indexedAt = getLong(columns.indexedAt),
            modelVersion = getNullableString(columns.modelVersion),
            error = getNullableString(columns.error),
            retryCount = getInt(columns.retryCount)
        )

    private fun Cursor.photoRecordColumns(): PhotoRecordColumns =
        PhotoRecordColumns(
            mediaId = column("media_id"),
            uri = column("uri"),
            displayName = column("display_name"),
            mimeType = column("mime_type"),
            width = column("width"),
            height = column("height"),
            sizeBytes = column("size_bytes"),
            dateAdded = column("date_added"),
            dateModified = column("date_modified"),
            status = column("status"),
            vectorOffset = column("vector_offset"),
            vectorDim = column("vector_dim"),
            indexedAt = column("indexed_at"),
            modelVersion = column("model_version"),
            error = column("error"),
            retryCount = column("retry_count")
        )

    private fun Cursor.column(name: String): Int = getColumnIndexOrThrow(name)

    private fun Cursor.getNullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun statusFromDb(value: String): IndexStatus =
        IndexStatus.entries.firstOrNull { it.dbValue == value } ?: IndexStatus.Pending

    private fun ensureQueryCacheModelKeyColumn(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA table_info(query_embedding_cache)", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.column("name")) == "model_key") return
            }
        }
        db.execSQL("ALTER TABLE query_embedding_cache ADD COLUMN model_key TEXT NOT NULL DEFAULT ''")
        db.execSQL("DELETE FROM query_embedding_cache")
    }

    companion object {
        private const val TAG = "PhotoIndexStore"
        private const val QUERY_CACHE_LIMIT = 100
        private val PHOTO_RECORD_COLUMNS = arrayOf(
            "media_id",
            "uri",
            "display_name",
            "mime_type",
            "width",
            "height",
            "size_bytes",
            "date_added",
            "date_modified",
            "status",
            "vector_offset",
            "vector_dim",
            "indexed_at",
            "model_version",
            "error",
            "retry_count"
        )
    }
}

private data class PhotoRecordColumns(
    val mediaId: Int,
    val uri: Int,
    val displayName: Int,
    val mimeType: Int,
    val width: Int,
    val height: Int,
    val sizeBytes: Int,
    val dateAdded: Int,
    val dateModified: Int,
    val status: Int,
    val vectorOffset: Int,
    val vectorDim: Int,
    val indexedAt: Int,
    val modelVersion: Int,
    val error: Int,
    val retryCount: Int
)

data class IndexedUpdate(
    val mediaId: Long,
    val vectorOffset: Long,
    val vectorDim: Int
)

private data class ExistingPhotoIndex(
    val dateModified: Long,
    val sizeBytes: Long,
    val status: IndexStatus,
    val vectorOffset: Long,
    val vectorDim: Int,
    val indexedAt: Long,
    val modelVersion: String?,
    val error: String?,
    val retryCount: Int
)
