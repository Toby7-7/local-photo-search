package com.photosearch.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.Options

internal data class PhotoThumbnailRequest(
    val mediaId: Long,
    val uri: Uri,
    val dateModified: Long,
    val sizeBytes: Long,
    val sizePx: Int = DEFAULT_THUMBNAIL_SIZE_PX
) {
    fun cacheKey(): String =
        photoThumbnailCacheKey(mediaId, dateModified, sizeBytes, sizePx)
}

internal fun photoThumbnailCacheKey(
    mediaId: Long,
    dateModified: Long,
    sizeBytes: Long,
    sizePx: Int
): String =
    "photo-thumbnail:$mediaId:$dateModified:$sizeBytes:$sizePx"

internal fun configurePhotoThumbnailImageLoader(context: Context) {
    val appContext = context.applicationContext
    SingletonImageLoader.setSafe {
        ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(appContext, THUMBNAIL_MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache(null)
            .diskCachePolicy(CachePolicy.DISABLED)
            .components {
                add(PhotoThumbnailKeyer())
                add(PhotoThumbnailFetcher.Factory(appContext))
            }
            .build()
    }
}

internal class PhotoThumbnailKeyer : Keyer<PhotoThumbnailRequest> {
    override fun key(data: PhotoThumbnailRequest, options: Options): String =
        data.cacheKey()
}

private class PhotoThumbnailFetcher(
    private val context: Context,
    private val data: PhotoThumbnailRequest
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bitmap = loadThumbnail() ?: return null
        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    private fun loadThumbnail(): Bitmap? =
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(data.uri, Size(data.sizePx, data.sizePx), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver,
                    data.mediaId,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null
                )
            }
        }.getOrNull()

    class Factory(private val context: Context) : Fetcher.Factory<PhotoThumbnailRequest> {
        override fun create(
            data: PhotoThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher =
            PhotoThumbnailFetcher(context, data)
    }
}

internal const val DEFAULT_THUMBNAIL_SIZE_PX = 256

private const val THUMBNAIL_MEMORY_CACHE_PERCENT = 0.10
