package com.photosearch.app.search

internal data class VectorCacheFingerprint(
    val indexedRecordCount: Int,
    val vectorFileLengthBytes: Long
)

internal data class ValidVectorMetadata(
    val offsets: LongArray,
    val floatOffsets: IntArray,
    val dims: IntArray,
    val records: List<PhotoRecord>,
    val skippedCount: Int
)

internal object VectorSearchEngine {
    data class TopKResult(
        val scores: FloatArray,
        val indexes: IntArray,
        val size: Int,
        val compared: Int,
        val engine: String = ENGINE_KOTLIN
    )

    fun isCacheFresh(
        cachedFingerprint: VectorCacheFingerprint?,
        currentFingerprint: VectorCacheFingerprint,
        hasCompletePayload: Boolean
    ): Boolean =
        hasCompletePayload && cachedFingerprint == currentFingerprint

    fun filterValidMetadata(
        records: List<PhotoRecord>,
        vectorFileLengthBytes: Long
    ): ValidVectorMetadata {
        if (records.isEmpty() || vectorFileLengthBytes <= 0L) {
            return ValidVectorMetadata(LongArray(0), IntArray(0), IntArray(0), emptyList(), records.size)
        }

        val validOffsets = LongArray(records.size)
        val validFloatOffsets = IntArray(records.size)
        val validDims = IntArray(records.size)
        val validRecords = ArrayList<PhotoRecord>(records.size)
        var write = 0
        var skipped = 0

        records.forEach { record ->
            val offset = record.vectorOffset
            val dim = record.vectorDim
            val byteCount = dim.toLong() * Float.SIZE_BYTES
            val floatOffset = offset / Float.SIZE_BYTES
            val valid = offset >= 0L &&
                dim > 0 &&
                offset % Float.SIZE_BYTES == 0L &&
                floatOffset <= Int.MAX_VALUE &&
                byteCount > 0L &&
                offset <= vectorFileLengthBytes - byteCount
            if (valid) {
                validOffsets[write] = offset
                validFloatOffsets[write] = floatOffset.toInt()
                validDims[write] = dim
                validRecords += record
                write += 1
            } else {
                skipped += 1
            }
        }

        return ValidVectorMetadata(
            offsets = validOffsets.copyOf(write),
            floatOffsets = validFloatOffsets.copyOf(write),
            dims = validDims.copyOf(write),
            records = validRecords,
            skippedCount = skipped
        )
    }

    fun searchTopK(
        vectors: FloatArray,
        offsets: LongArray,
        dims: IntArray,
        queryEmbedding: FloatArray,
        threshold: Float,
        topK: Int
    ): TopKResult =
        searchTopK(
            vectors = vectors,
            vectorFloatOffsets = byteOffsetsToFloatOffsets(offsets),
            dims = dims,
            queryEmbedding = queryEmbedding,
            threshold = threshold,
            topK = topK
        )

    fun searchTopK(
        vectors: FloatArray,
        vectorFloatOffsets: IntArray,
        dims: IntArray,
        queryEmbedding: FloatArray,
        threshold: Float,
        topK: Int
    ): TopKResult {
        NativeVectorSearch.searchTopK(
            vectors = vectors,
            vectorFloatOffsets = vectorFloatOffsets,
            dims = dims,
            queryEmbedding = queryEmbedding,
            threshold = threshold,
            topK = topK
        )?.let { return it }

        return searchTopKRange(
            vectors = vectors,
            vectorFloatOffsets = vectorFloatOffsets,
            dims = dims,
            queryEmbedding = queryEmbedding,
            threshold = threshold,
            topK = topK,
            start = 0,
            count = vectorFloatOffsets.size
        )
    }

    fun searchTopKRange(
        vectors: FloatArray,
        vectorFloatOffsets: IntArray,
        dims: IntArray,
        queryEmbedding: FloatArray,
        threshold: Float,
        topK: Int,
        start: Int,
        count: Int
    ): TopKResult {
        val limit = topK.coerceAtLeast(1)
        val heapScores = FloatArray(limit)
        val heapIndexes = IntArray(limit)
        var heapSize = 0
        var compared = 0
        val querySize = queryEmbedding.size
        val end = (start + count).coerceAtMost(vectorFloatOffsets.size)
        val useThreshold = threshold > 0f

        for (i in start until end) {
            if (dims[i] != querySize) continue
            val vecOffset = vectorFloatOffsets[i]

            var sum0 = 0.0f
            var sum1 = 0.0f
            var sum2 = 0.0f
            var sum3 = 0.0f
            var j = 0
            val unrollEnd = querySize - 3
            while (j < unrollEnd) {
                sum0 += queryEmbedding[j] * vectors[vecOffset + j]
                sum1 += queryEmbedding[j + 1] * vectors[vecOffset + j + 1]
                sum2 += queryEmbedding[j + 2] * vectors[vecOffset + j + 2]
                sum3 += queryEmbedding[j + 3] * vectors[vecOffset + j + 3]
                j += 4
            }
            var sum = sum0 + sum1 + sum2 + sum3
            while (j < querySize) {
                sum += queryEmbedding[j] * vectors[vecOffset + j]
                j++
            }
            compared += 1

            if (!useThreshold || sum >= threshold) {
                heapSize = offerTopK(heapScores, heapIndexes, heapSize, limit, sum, i)
            }
        }

        return heapToResult(heapScores, heapIndexes, heapSize, compared)
    }

    fun computeScores(
        vectors: FloatArray,
        offsets: LongArray,
        dims: IntArray,
        query: FloatArray,
        start: Int,
        count: Int,
        scores: FloatArray,
        indexes: IntArray
    ): Int =
        computeScores(
            vectors = vectors,
            vectorFloatOffsets = byteOffsetsToFloatOffsets(offsets),
            dims = dims,
            query = query,
            start = start,
            count = count,
            scores = scores,
            indexes = indexes
        )

    fun computeScores(
        vectors: FloatArray,
        vectorFloatOffsets: IntArray,
        dims: IntArray,
        query: FloatArray,
        start: Int,
        count: Int,
        scores: FloatArray,
        indexes: IntArray
    ): Int {
        var valid = 0
        val querySize = query.size

        for (i in start until start + count) {
            val dim = dims[i]
            if (dim != querySize) continue

            val vecOffset = vectorFloatOffsets[i]

            var sum0 = 0.0f
            var sum1 = 0.0f
            var sum2 = 0.0f
            var sum3 = 0.0f
            var j = 0
            val unrollEnd = querySize - 3
            while (j < unrollEnd) {
                sum0 += query[j] * vectors[vecOffset + j]
                sum1 += query[j + 1] * vectors[vecOffset + j + 1]
                sum2 += query[j + 2] * vectors[vecOffset + j + 2]
                sum3 += query[j + 3] * vectors[vecOffset + j + 3]
                j += 4
            }
            var sum = sum0 + sum1 + sum2 + sum3
            while (j < querySize) {
                sum += query[j] * vectors[vecOffset + j]
                j++
            }

            scores[valid] = sum
            indexes[valid] = i
            valid++
        }
        return valid
    }

    fun filterByThreshold(scores: FloatArray, indexes: IntArray, count: Int, threshold: Float): Int {
        var write = 0
        for (read in 0 until count) {
            if (scores[read] >= threshold) {
                if (write != read) {
                    scores[write] = scores[read]
                    indexes[write] = indexes[read]
                }
                write++
            }
        }
        return write
    }

    fun topKSelection(scores: FloatArray, indexes: IntArray, count: Int, k: Int): TopKResult {
        val limit = k.coerceAtLeast(1)
        if (count <= limit) {
            sortPairsByScoreDescending(scores, indexes, count)
            return TopKResult(scores, indexes, count, count)
        }

        val heapScores = FloatArray(limit)
        val heapIndexes = IntArray(limit)
        var heapSize = 0
        for (i in 0 until count) {
            val score = scores[i]
            if (heapSize < limit) {
                heapScores[heapSize] = score
                heapIndexes[heapSize] = indexes[i]
                siftUp(heapScores, heapIndexes, heapSize)
                heapSize += 1
            } else if (score > heapScores[0]) {
                heapScores[0] = score
                heapIndexes[0] = indexes[i]
                siftDown(heapScores, heapIndexes, 0, heapSize)
            }
        }

        val resultScores = FloatArray(heapSize)
        val resultIndexes = IntArray(heapSize)
        for (i in heapSize - 1 downTo 0) {
            resultScores[i] = heapScores[0]
            resultIndexes[i] = heapIndexes[0]
            heapSize -= 1
            if (heapSize > 0) {
                heapScores[0] = heapScores[heapSize]
                heapIndexes[0] = heapIndexes[heapSize]
                siftDown(heapScores, heapIndexes, 0, heapSize)
            }
        }

        return TopKResult(resultScores, resultIndexes, resultScores.size, count)
    }

    fun mergeTopK(partials: List<TopKResult>, k: Int): TopKResult {
        if (partials.size == 1) return partials.first()

        val totalCount = partials.sumOf { it.size }
        val totalCompared = partials.sumOf { it.compared }
        if (totalCount == 0) return TopKResult(FloatArray(0), IntArray(0), 0, totalCompared)

        val allScores = FloatArray(totalCount)
        val allIndexes = IntArray(totalCount)
        var pos = 0
        for (partial in partials) {
            for (i in 0 until partial.size) {
                allScores[pos] = partial.scores[i]
                allIndexes[pos] = partial.indexes[i]
                pos++
            }
        }

        if (totalCount <= k) {
            sortPairsByScoreDescending(allScores, allIndexes, totalCount)
            return TopKResult(allScores, allIndexes, totalCount, totalCompared)
        }

        val selected = topKSelection(allScores, allIndexes, totalCount, k)
        return selected.copy(compared = totalCompared)
    }

    private fun offerTopK(
        heapScores: FloatArray,
        heapIndexes: IntArray,
        heapSize: Int,
        limit: Int,
        score: Float,
        index: Int
    ): Int {
        if (heapSize < limit) {
            heapScores[heapSize] = score
            heapIndexes[heapSize] = index
            siftUp(heapScores, heapIndexes, heapSize)
            return heapSize + 1
        }
        if (score > heapScores[0]) {
            heapScores[0] = score
            heapIndexes[0] = index
            siftDown(heapScores, heapIndexes, 0, heapSize)
        }
        return heapSize
    }

    private fun heapToResult(
        heapScores: FloatArray,
        heapIndexes: IntArray,
        heapSize: Int,
        compared: Int
    ): TopKResult {
        val resultScores = FloatArray(heapSize)
        val resultIndexes = IntArray(heapSize)
        var size = heapSize
        for (i in heapSize - 1 downTo 0) {
            resultScores[i] = heapScores[0]
            resultIndexes[i] = heapIndexes[0]
            size -= 1
            if (size > 0) {
                heapScores[0] = heapScores[size]
                heapIndexes[0] = heapIndexes[size]
                siftDown(heapScores, heapIndexes, 0, size)
            }
        }
        return TopKResult(resultScores, resultIndexes, heapSize, compared)
    }

    private fun byteOffsetsToFloatOffsets(offsets: LongArray): IntArray =
        IntArray(offsets.size) { (offsets[it] / Float.SIZE_BYTES).toInt() }

    private fun sortPairsByScoreDescending(scores: FloatArray, recordIndexes: IntArray, count: Int) {
        if (count <= 1) return
        quickSort(scores, recordIndexes, 0, count - 1)
    }

    private fun quickSort(scores: FloatArray, recordIndexes: IntArray, left: Int, right: Int) {
        var low = left
        var high = right
        while (low < high) {
            if (high - low < INSERTION_SORT_THRESHOLD) {
                insertionSort(scores, recordIndexes, low, high)
                return
            }
            val pivot = scores[(low + high) ushr 1]
            var i = low
            var j = high
            while (i <= j) {
                while (scores[i] > pivot) i += 1
                while (scores[j] < pivot) j -= 1
                if (i <= j) {
                    swap(scores, recordIndexes, i, j)
                    i += 1
                    j -= 1
                }
            }
            if (j - low < high - i) {
                if (low < j) quickSort(scores, recordIndexes, low, j)
                low = i
            } else {
                if (i < high) quickSort(scores, recordIndexes, i, high)
                high = j
            }
        }
    }

    private fun insertionSort(scores: FloatArray, recordIndexes: IntArray, left: Int, right: Int) {
        for (i in left + 1..right) {
            val score = scores[i]
            val recordIndex = recordIndexes[i]
            var j = i - 1
            while (j >= left && scores[j] < score) {
                scores[j + 1] = scores[j]
                recordIndexes[j + 1] = recordIndexes[j]
                j -= 1
            }
            scores[j + 1] = score
            recordIndexes[j + 1] = recordIndex
        }
    }

    private fun swap(scores: FloatArray, recordIndexes: IntArray, first: Int, second: Int) {
        if (first == second) return
        val score = scores[first]
        scores[first] = scores[second]
        scores[second] = score
        val recordIndex = recordIndexes[first]
        recordIndexes[first] = recordIndexes[second]
        recordIndexes[second] = recordIndex
    }

    private fun siftUp(heapScores: FloatArray, heapIndexes: IntArray, start: Int) {
        var child = start
        val score = heapScores[child]
        val index = heapIndexes[child]
        while (child > 0) {
            val parent = (child - 1) ushr 1
            if (heapScores[parent] <= score) break
            heapScores[child] = heapScores[parent]
            heapIndexes[child] = heapIndexes[parent]
            child = parent
        }
        heapScores[child] = score
        heapIndexes[child] = index
    }

    private fun siftDown(heapScores: FloatArray, heapIndexes: IntArray, start: Int, size: Int) {
        var parent = start
        val score = heapScores[parent]
        val index = heapIndexes[parent]
        while (true) {
            var child = parent * 2 + 1
            if (child >= size) break
            val right = child + 1
            if (right < size && heapScores[right] < heapScores[child]) {
                child = right
            }
            if (heapScores[child] >= score) break
            heapScores[parent] = heapScores[child]
            heapIndexes[parent] = heapIndexes[child]
            parent = child
        }
        heapScores[parent] = score
        heapIndexes[parent] = index
    }

    private const val INSERTION_SORT_THRESHOLD = 24
    const val ENGINE_KOTLIN = "kotlin"
    const val ENGINE_NATIVE = "native"
}
