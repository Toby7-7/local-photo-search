package com.photosearch.app.search

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class VectorStore(private val file: File) {
    @Volatile
    private var persistentMapping: MappedVectorReadSession? = null
    private val mappingLock = Any()

    @Synchronized
    fun appendBatch(data: FloatArray, vectorDim: Int, count: Int): List<Long> {
        require(vectorDim > 0) { "vectorDim must be positive" }
        require(count >= 0) { "count must be non-negative" }
        require(data.size >= vectorDim * count) {
            "Not enough vector data: ${data.size}, expected ${vectorDim * count}"
        }
        if (count == 0) return emptyList()
        file.parentFile?.mkdirs()
        invalidateMapping()
        RandomAccessFile(file, "rw").use { raf ->
            val firstOffset = raf.length()
            raf.seek(firstOffset)
            val byteCount = vectorDim * count * Float.SIZE_BYTES
            val bytes = java.nio.ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
            val floats = bytes.asFloatBuffer()
            floats.put(data, 0, vectorDim * count)
            raf.write(bytes.array(), bytes.arrayOffset(), byteCount)
            val vectorBytes = vectorDim * Float.SIZE_BYTES
            return List(count) { index -> firstOffset + index.toLong() * vectorBytes }
        }
    }

    /**
     * Returns a persistent mmap session that stays alive across searches.
     * The mapping is invalidated when new vectors are appended.
     */
    fun persistentMappedSession(): MappedVectorReadSession {
        synchronized(mappingLock) {
            val existing = persistentMapping
            if (existing != null && existing.isValid()) return existing
            val session = createMapping()
            persistentMapping = session
            return session
        }
    }

    private fun createMapping(): MappedVectorReadSession {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            require(length <= Int.MAX_VALUE) { "Vector file too large to map: $length bytes" }
            raf.channel.use { channel ->
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, length)
                mapped.order(ByteOrder.LITTLE_ENDIAN)
                // MappedByteBuffer keeps the mapping alive after channel/raf close
                return MappedVectorReadSession(mapped, length)
            }
        }
    }

    private fun invalidateMapping() {
        synchronized(mappingLock) {
            // Searches may still hold the old mapping; drop only our reference and let GC reclaim it.
            persistentMapping = null
        }
    }

    fun lengthBytes(): Long = if (file.exists()) file.length() else 0L

    @Synchronized
    fun clear() {
        invalidateMapping()
        if (file.exists()) {
            RandomAccessFile(file, "rw").use { it.setLength(0L) }
        }
    }
}

class MappedVectorReadSession internal constructor(
    private val buffer: MappedByteBuffer,
    private val fileLength: Long
) {
    /**
     * Read all vectors from the mapped buffer into a contiguous FloatArray.
     * This eliminates per-float JNI overhead for subsequent dot product calculations.
     */
    fun readAllVectors(): FloatArray {
        val totalFloats = (fileLength / Float.SIZE_BYTES).toInt()
        val vectors = FloatArray(totalFloats)
        buffer.asFloatBuffer().get(vectors)
        return vectors
    }

    fun isValid(): Boolean = fileLength > 0
}
