package com.photosearch.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.imageLoader
import com.photosearch.app.R
import com.photosearch.app.search.PhotoRecord
import com.photosearch.app.search.PhotoSearchController
import com.photosearch.app.search.PhotoSearchUiState
import com.photosearch.app.search.SearchResult
import com.photosearch.app.search.SearchTiming

@Composable
fun PhotoSearchApp(appContext: Context) {
    val controller = remember { PhotoSearchController(appContext) }
    val state by controller.state.collectAsState()

    LaunchedEffect(controller) {
        controller.start()
    }

    PhotoSearchTheme {
        PhotoSearchScreen(
            state = state,
            controller = controller
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoSearchScreen(
    state: PhotoSearchUiState,
    controller: PhotoSearchController
) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        controller.onPermissionResult()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SearchHeader(
                state = state,
                onQuery = controller::updateQuery
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StatusPanel(
                state = state,
                onPermission = { permissionLauncher.launch(permission) },
                onStart = controller::scanAndIndex,
                onPause = controller::pauseIndexing,
                onRetry = controller::retryFailed,
                onRetryOne = { mediaId -> controller.retryFailed(mediaId) },
                onShowFailures = controller::refreshFailedRecords,
                onThreshold = controller::updateScoreThreshold,
                onPreloadSearchModel = controller::updatePreloadSearchModel,
                onPreloadIndexModel = controller::updatePreloadIndexModel,
                onRebuild = controller::rebuildIndex,
                onOpenPhoto = { record -> openPhoto(context, record) }
            )
            ResultGrid(
                results = state.visibleResults,
                resultRevision = state.resultRevision,
                canLoadMore = state.visibleResults.size < state.filteredResults.size,
                onLoadMore = controller::loadNextPage,
                onOpenPhoto = { record -> openPhoto(context, record) }
            )
        }
    }
}

@Composable
private fun SearchHeader(
    state: PhotoSearchUiState,
    onQuery: (String) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_placeholder), lineHeight = 22.sp) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

@Composable
private fun StatusPanel(
    state: PhotoSearchUiState,
    onPermission: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onRetryOne: (Long) -> Unit,
    onShowFailures: () -> Unit,
    onThreshold: (Float) -> Unit,
    onPreloadSearchModel: (Boolean) -> Unit,
    onPreloadIndexModel: (Boolean) -> Unit,
    onRebuild: () -> Unit,
    onOpenPhoto: (PhotoRecord) -> Unit
) {
    var showFailures by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showRebuildConfirm by remember { mutableStateOf(false) }
    val searchTimingScroll = rememberScrollState()

    LaunchedEffect(state.searchTiming) {
        searchTimingScroll.scrollTo(0)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatChip(
                    label = stringResource(R.string.stat_indexed, state.stats.indexed.toString()),
                    value = ""
                )
                StatChip(
                    label = stringResource(R.string.stat_pending, state.stats.pending.toString()),
                    value = ""
                )
                StatChip(
                    label = stringResource(R.string.stat_failed, state.stats.failed.toString()),
                    value = "",
                    enabled = state.stats.failed > 0,
                    onClick = {
                        showFailures = true
                        onShowFailures()
                    }
                )
            }
            if (shouldShowIndexStatus(state)) {
                Text(
                    text = buildSpeedLine1(state),
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildSpeedLine2(state),
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildSpeedLine3(state),
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val statusMessage = visibleStatusMessage(state)
            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.query.isNotBlank() && state.searchTiming != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .horizontalScroll(searchTimingScroll)
                ) {
                    Text(
                        text = buildSearchTimingLine(state.searchTiming),
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!state.hasPermission) {
                    Button(
                        onClick = onPermission,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.grant_permission), maxLines = 1)
                    }
                } else {
                    Button(
                        onClick = if (state.stats.running) onPause else onStart,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                    ) {
                        Icon(
                            if (state.stats.running) AppIcons.Pause else AppIcons.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(indexButtonText(state), maxLines = 1)
                    }
                }
                if (state.stats.failed > 0 && !state.stats.running) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Text(stringResource(R.string.retry_failed), maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = { showSettings = true },
                    enabled = !state.stats.running,
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                ) {
                    Text(stringResource(R.string.settings), maxLines = 1)
                }
            }
        }
    }

    if (showFailures) {
        FailureDialog(
            failedRecords = state.failedRecords,
            failedCount = state.stats.failed,
            running = state.stats.running,
            onDismiss = { showFailures = false },
            onRetryAll = onRetry,
            onRetryOne = onRetryOne,
            onOpenPhoto = onOpenPhoto,
            onRebuild = { showRebuildConfirm = true }
        )
    }

    if (showSettings) {
        SettingsDialog(
            threshold = state.scoreThreshold,
            preloadSearchModel = state.preloadSearchModel,
            preloadIndexModel = state.preloadIndexModel,
            onThreshold = onThreshold,
            onPreloadSearchModel = onPreloadSearchModel,
            onPreloadIndexModel = onPreloadIndexModel,
            running = state.stats.running,
            onRebuild = {
                showSettings = false
                showRebuildConfirm = true
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showRebuildConfirm) {
        RebuildConfirmDialog(
            onDismiss = { showRebuildConfirm = false },
            onConfirm = {
                showRebuildConfirm = false
                showFailures = false
                onRebuild()
            }
        )
    }
}

@Composable
private fun buildSpeedLine1(state: PhotoSearchUiState): String {
    val e2e = state.stats.recentImagesPerSecond
    val totalMs = perImageMs(state.stats.recentWorkMs ?: state.stats.recentBatchMs, state)
    val speedPart = e2e?.let { "%.2f/s".format(it) } ?: "--/s"
    val perImagePart = formatMs(totalMs)
    return buildString {
        append(stringResource(R.string.speed_label, speedPart))
        append(" · ")
        append(stringResource(R.string.per_image_time, perImagePart))
    }
}

@Composable
private fun buildSpeedLine2(state: PhotoSearchUiState): String {
    val decode = perImageMs(state.stats.recentDecodeMs, state)
    val tensor = perImageMs(state.stats.recentTensorMs, state)
    val inference = perImageMs(state.stats.recentInferenceMs, state)
    return buildString {
        append(stringResource(R.string.decode, formatMs(decode)))
        append(" · ")
        append(stringResource(R.string.tensor, formatMs(tensor)))
        append(" · ")
        append(stringResource(R.string.inference, formatMs(inference)))
    }
}

@Composable
private fun buildSpeedLine3(state: PhotoSearchUiState): String {
    val write = perImageMs(state.stats.recentWriteMs, state)
    val other = perImageOtherMs(state)
    return buildString {
        append(stringResource(R.string.write, formatMs(write)))
        append(" · ")
        append(stringResource(R.string.other, formatMs(other)))
        append(" · ")
        append(stringResource(R.string.total_equals_duration))
    }
}

@Composable
private fun buildSearchTimingLine(timing: SearchTiming): String =
    buildString {
        append(stringResource(R.string.search, formatMs(timing.totalMs)))
        append(" · ")
        append(stringResource(R.string.model, formatMs(timing.modelLoadMs)))
        append(" · ")
        append(stringResource(R.string.text, formatMs(timing.textPrepareMs + timing.textInferenceMs)))
        append(" · ")
        append(stringResource(R.string.retrieval, formatMs(timing.indexedReadMs + timing.vectorSearchMs)))
        append(" · ")
        append(stringResource(R.string.build, formatMs(timing.resultBuildMs)))
        append(" · ")
        append(stringResource(R.string.compare, timing.comparedPhotos))
        append(" · ")
        append(stringResource(R.string.results_count, timing.resultCount))
    }

private fun perImageMs(batchMs: Double?, state: PhotoSearchUiState): Double? {
    val batchSize = state.stats.batchSize.coerceAtLeast(1)
    return batchMs?.div(batchSize)
}

private fun perImageOtherMs(state: PhotoSearchUiState): Double? {
    val total = perImageMs(state.stats.recentWorkMs ?: state.stats.recentBatchMs, state) ?: return null
    val known = listOfNotNull(
        perImageMs(state.stats.recentDecodeMs, state),
        perImageMs(state.stats.recentTensorMs, state),
        perImageMs(state.stats.recentInferenceMs, state),
        perImageMs(state.stats.recentWriteMs, state)
    ).sum()
    return (total - known).coerceAtLeast(0.0)
}

private fun shouldShowIndexStatus(state: PhotoSearchUiState): Boolean =
    state.stats.running

@Composable
private fun visibleStatusMessage(state: PhotoSearchUiState): String =
    state.searchMessage.ifBlank {
        val message = state.stats.message
        when {
            state.stats.running -> ""
            message == "idle" -> ""
            message == "index up to date" -> ""
            else -> message
        }
    }

private fun formatMs(value: Double?): String =
    value?.let { "%.0fms".format(it) } ?: "--"

private fun formatScore(value: Float): String =
    "%.2f".format(value)

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingsDialog(
    threshold: Float,
    preloadSearchModel: Boolean,
    preloadIndexModel: Boolean,
    onThreshold: (Float) -> Unit,
    onPreloadSearchModel: (Boolean) -> Unit,
    onPreloadIndexModel: (Boolean) -> Unit,
    running: Boolean,
    onRebuild: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.confidence_threshold, formatScore(threshold)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.threshold_description),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = threshold,
                    onValueChange = onThreshold,
                    valueRange = 0.0f..0.5f,
                    steps = 49
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.00", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatScore(threshold), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("0.50", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SettingSwitchRow(
                    title = stringResource(R.string.preload_search_model),
                    checked = preloadSearchModel,
                    enabled = true,
                    onCheckedChange = onPreloadSearchModel
                )
                SettingSwitchRow(
                    title = stringResource(R.string.preload_index_model),
                    checked = preloadIndexModel,
                    enabled = !running,
                    onCheckedChange = onPreloadIndexModel
                )
                OutlinedButton(
                    onClick = onRebuild,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.rebuild_index))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = { onThreshold(0.0f) }) {
                Text(stringResource(R.string.reset))
            }
        }
    )
}

@Composable
private fun FailureDialog(
    failedRecords: List<PhotoRecord>,
    failedCount: Int,
    running: Boolean,
    onDismiss: () -> Unit,
    onRetryAll: () -> Unit,
    onRetryOne: (Long) -> Unit,
    onRebuild: () -> Unit,
    onOpenPhoto: (PhotoRecord) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.failed_photos_title, failedCount)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failedRecords.isEmpty()) {
                    Text(
                        text = stringResource(R.string.reading_failures),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.showing_failures_recent, failedRecords.size),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(failedRecords, key = { it.mediaId }) { record ->
                            FailedRecordRow(
                                record = record,
                                running = running,
                                onRetryOne = onRetryOne,
                                onOpenPhoto = onOpenPhoto
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onRetryAll,
                enabled = failedCount > 0 && !running
            ) {
                Text(stringResource(R.string.retry_all_failed))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onRebuild,
                    enabled = !running
                ) {
                    Text(stringResource(R.string.rebuild_index))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    )
}

@Composable
private fun FailedRecordRow(
    record: PhotoRecord,
    running: Boolean,
    onRetryOne: (Long) -> Unit,
    onOpenPhoto: (PhotoRecord) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = record.displayName ?: stringResource(R.string.media_fallback, record.mediaId),
                fontSize = 13.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.failure_id_retry, record.mediaId, record.retryCount),
                fontSize = 11.sp,
                lineHeight = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = record.error ?: stringResource(R.string.no_error_recorded),
                fontSize = 12.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onOpenPhoto(record) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text(stringResource(R.string.open_photo))
                }
                TextButton(
                    onClick = { onRetryOne(record.mediaId) },
                    enabled = !running,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text(stringResource(R.string.retry_this))
                }
            }
        }
    }
}

@Composable
private fun RebuildConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_rebuild_index)) },
        text = {
            Text(stringResource(R.string.rebuild_confirm_text))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.confirm_rebuild))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun openPhoto(context: Context, record: PhotoRecord) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(record.parsedUri, record.mimeType ?: "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.no_gallery_app), Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, context.getString(R.string.no_permission_to_open), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun indexButtonText(state: PhotoSearchUiState): String {
    val status = when {
        state.stats.message.startsWith("scanning") -> stringResource(R.string.status_scanning)
        state.stats.message.startsWith("preparing") -> stringResource(R.string.status_preparing)
        state.stats.message.startsWith("indexing") -> stringResource(R.string.status_indexing)
        state.stats.message.startsWith("retrying") -> stringResource(R.string.status_retrying)
        state.stats.message.startsWith("paused") -> stringResource(R.string.continue_index)
        state.stats.message.startsWith("index up to date") -> stringResource(R.string.start_index)
        state.stats.running -> stringResource(R.string.status_processing)
        else -> stringResource(R.string.start_index)
    }
    return if (state.stats.running) "$status${stringResource(R.string.tap_to_pause)}" else status
}

@Composable
private fun StatChip(
    label: String,
    value: String,
    enabled: Boolean = false,
    onClick: () -> Unit = {}
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(30.dp),
        label = {
            Text(
                text = if (value.isNotBlank()) "$label $value" else label,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    )
}

@Composable
private fun ResultGrid(
    results: List<SearchResult>,
    resultRevision: Long,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenPhoto: (PhotoRecord) -> Unit
) {
    val gridState = rememberLazyGridState()
    val shouldLoadMore by remember(canLoadMore, results.size) {
        derivedStateOf {
            if (!canLoadMore || results.isEmpty()) {
                false
            } else {
                val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= results.lastIndex - 12
            }
        }
    }

    LaunchedEffect(resultRevision) {
        gridState.scrollToItem(0)
    }

    LaunchedEffect(shouldLoadMore, results.size) {
        if (shouldLoadMore) onLoadMore()
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(
            items = results,
            key = { _, result -> result.record.mediaId }
        ) { _, result ->
            PhotoTile(
                result = result,
                onOpenPhoto = onOpenPhoto
            )
        }
    }
}

@Composable
private fun PhotoTile(
    result: SearchResult,
    onOpenPhoto: (PhotoRecord) -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpenPhoto(result.record) }
    ) {
        AsyncThumbnail(
            record = result.record,
            modifier = Modifier.fillMaxSize()
        )
        if (!result.score.isNaN()) {
            Text(
                text = "%.2f".format(result.score),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.84f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                fontSize = 11.sp,
                lineHeight = 12.sp
            )
        }
    }
}

@Composable
private fun AsyncThumbnail(
    record: PhotoRecord,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val request = remember(record.mediaId, record.uri, record.dateModified, record.sizeBytes) {
        PhotoThumbnailRequest(
            mediaId = record.mediaId,
            uri = record.parsedUri,
            dateModified = record.dateModified,
            sizeBytes = record.sizeBytes
        )
    }
    AsyncImage(
        model = request,
        contentDescription = record.displayName,
        imageLoader = context.imageLoader,
        modifier = modifier,
        contentScale = ContentScale.Crop
    )
}
