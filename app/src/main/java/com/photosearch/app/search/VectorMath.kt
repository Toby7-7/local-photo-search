package com.photosearch.app.search

import kotlin.math.sqrt

object VectorMath {
    fun normalizeInPlace(values: FloatArray, offset: Int = 0, length: Int = values.size - offset) {
        var sum = 0.0
        for (i in 0 until length) {
            val value = values[offset + i]
            sum += value * value
        }
        if (sum <= 0.0) return
        val inv = (1.0 / sqrt(sum)).toFloat()
        for (i in 0 until length) {
            values[offset + i] *= inv
        }
    }

    fun dot(left: FloatArray, right: FloatArray, rightOffset: Int, length: Int): Float {
        var sum = 0f
        var i = 0
        while (i < length) {
            sum += left[i] * right[rightOffset + i]
            i++
        }
        return sum
    }
}

