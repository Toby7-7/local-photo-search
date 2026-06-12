package com.photosearch.app.search

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

class MediaStoreScanner(private val context: Context) {
    fun hasImagePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val readGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val partialGranted = Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        return readGranted || partialGranted
    }

    fun scanImages(): List<MediaImage> =
        queryImages(selection = null, selectionArgs = null, limit = null)

    fun latestImage(): MediaImage? =
        queryImages(selection = null, selectionArgs = null, limit = 1).firstOrNull()

    fun scanImagesNewerThan(dateModified: Long, mediaId: Long): List<MediaImage> =
        queryImages(
            selection = "${MediaStore.Images.Media.DATE_MODIFIED} > ? OR " +
                "(${MediaStore.Images.Media.DATE_MODIFIED} = ? AND ${MediaStore.Images.Media._ID} > ?)",
            selectionArgs = arrayOf(dateModified.toString(), dateModified.toString(), mediaId.toString()),
            limit = null
        )

    /**
     * 高效统计设备上的照片总数（不加载完整数据）
     */
    fun countImages(): Int {
        if (!hasImagePermission()) return 0
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            null
        )?.use { cursor ->
            return cursor.count
        }
        return 0
    }

    private fun queryImages(
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int?
    ): List<MediaImage> {
        if (!hasImagePermission()) return emptyList()
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val images = ArrayList<MediaImage>()
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC, ${MediaStore.Images.Media._ID} DESC"
        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                if (limit != null && images.size >= limit) break
                val id = cursor.getLong(idColumn)
                images += MediaImage(
                    mediaId = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = cursor.getStringOrNull(nameColumn),
                    mimeType = cursor.getStringOrNull(mimeColumn),
                    width = cursor.getIntOrZero(widthColumn),
                    height = cursor.getIntOrZero(heightColumn),
                    sizeBytes = cursor.getLongOrZero(sizeColumn),
                    dateAdded = cursor.getLongOrZero(addedColumn),
                    dateModified = cursor.getLongOrZero(modifiedColumn)
                )
            }
        }
        return images
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? =
    if (index >= 0 && !isNull(index)) getString(index) else null

private fun android.database.Cursor.getIntOrZero(index: Int): Int =
    if (index >= 0 && !isNull(index)) getInt(index) else 0

private fun android.database.Cursor.getLongOrZero(index: Int): Long =
    if (index >= 0 && !isNull(index)) getLong(index) else 0L
