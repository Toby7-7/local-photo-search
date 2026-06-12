#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <thread>
#include <vector>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

namespace {

constexpr int kMinParallelVectors = 4000;
constexpr int kMaxThreads = 4;

struct TopKHeap {
    explicit TopKHeap(int limit) : scores(limit), indexes(limit), limit(limit) {}

    std::vector<float> scores;
    std::vector<int> indexes;
    int limit = 0;
    int size = 0;

    void offer(float score, int index) {
        if (limit <= 0) return;
        if (size < limit) {
            scores[size] = score;
            indexes[size] = index;
            siftUp(size);
            size += 1;
            return;
        }
        if (score > scores[0]) {
            scores[0] = score;
            indexes[0] = index;
            siftDown(0, size);
        }
    }

    void sortDescending() {
        int heapSize = size;
        for (int i = heapSize - 1; i >= 0; --i) {
            swap(0, i);
            heapSize -= 1;
            if (heapSize > 0) {
                siftDown(0, heapSize);
            }
        }
    }

private:
    void siftUp(int start) {
        int child = start;
        const float score = scores[child];
        const int index = indexes[child];
        while (child > 0) {
            const int parent = (child - 1) >> 1;
            if (scores[parent] <= score) break;
            scores[child] = scores[parent];
            indexes[child] = indexes[parent];
            child = parent;
        }
        scores[child] = score;
        indexes[child] = index;
    }

    void siftDown(int start, int heapSize) {
        int parent = start;
        const float score = scores[parent];
        const int index = indexes[parent];
        while (true) {
            int child = parent * 2 + 1;
            if (child >= heapSize) break;
            const int right = child + 1;
            if (right < heapSize && scores[right] < scores[child]) {
                child = right;
            }
            if (scores[child] >= score) break;
            scores[parent] = scores[child];
            indexes[parent] = indexes[child];
            parent = child;
        }
        scores[parent] = score;
        indexes[parent] = index;
    }

    void swap(int first, int second) {
        if (first == second) return;
        std::swap(scores[first], scores[second]);
        std::swap(indexes[first], indexes[second]);
    }
};

struct SearchPartial {
    explicit SearchPartial(int limit) : heap(limit) {}

    TopKHeap heap;
    int compared = 0;
};

float dotProduct(const float* vector, const float* query, int dim) {
#if defined(__aarch64__) && (defined(__ARM_NEON) || defined(__ARM_NEON__))
    float32x4_t acc0 = vdupq_n_f32(0.0f);
    float32x4_t acc1 = vdupq_n_f32(0.0f);
    float32x4_t acc2 = vdupq_n_f32(0.0f);
    float32x4_t acc3 = vdupq_n_f32(0.0f);
    int j = 0;
    for (; j + 15 < dim; j += 16) {
        acc0 = vfmaq_f32(acc0, vld1q_f32(vector + j), vld1q_f32(query + j));
        acc1 = vfmaq_f32(acc1, vld1q_f32(vector + j + 4), vld1q_f32(query + j + 4));
        acc2 = vfmaq_f32(acc2, vld1q_f32(vector + j + 8), vld1q_f32(query + j + 8));
        acc3 = vfmaq_f32(acc3, vld1q_f32(vector + j + 12), vld1q_f32(query + j + 12));
    }
    float sum = vaddvq_f32(acc0) + vaddvq_f32(acc1) + vaddvq_f32(acc2) + vaddvq_f32(acc3);
    for (; j < dim; ++j) {
        sum += vector[j] * query[j];
    }
    return sum;
#elif defined(__ARM_NEON) || defined(__ARM_NEON__)
    float32x4_t acc0 = vdupq_n_f32(0.0f);
    float32x4_t acc1 = vdupq_n_f32(0.0f);
    int j = 0;
    for (; j + 7 < dim; j += 8) {
        acc0 = vmlaq_f32(acc0, vld1q_f32(vector + j), vld1q_f32(query + j));
        acc1 = vmlaq_f32(acc1, vld1q_f32(vector + j + 4), vld1q_f32(query + j + 4));
    }
    float lanes[4];
    vst1q_f32(lanes, vaddq_f32(acc0, acc1));
    float sum = lanes[0] + lanes[1] + lanes[2] + lanes[3];
    for (; j < dim; ++j) {
        sum += vector[j] * query[j];
    }
    return sum;
#else
    float sum0 = 0.0f;
    float sum1 = 0.0f;
    float sum2 = 0.0f;
    float sum3 = 0.0f;
    int j = 0;
    for (; j + 3 < dim; j += 4) {
        sum0 += vector[j] * query[j];
        sum1 += vector[j + 1] * query[j + 1];
        sum2 += vector[j + 2] * query[j + 2];
        sum3 += vector[j + 3] * query[j + 3];
    }
    float sum = sum0 + sum1 + sum2 + sum3;
    for (; j < dim; ++j) {
        sum += vector[j] * query[j];
    }
    return sum;
#endif
}

void searchRange(
    const float* vectors,
    int vectorLength,
    const int* vectorFloatOffsets,
    const int* dims,
    const float* query,
    int queryLength,
    float threshold,
    int start,
    int end,
    SearchPartial* partial
) {
    const bool useThreshold = threshold > 0.0f;
    for (int i = start; i < end; ++i) {
        const int dim = dims[i];
        if (dim != queryLength) continue;
        const int floatOffset = vectorFloatOffsets[i];
        if (floatOffset < 0 || floatOffset > vectorLength - queryLength) continue;
        const float score = dotProduct(vectors + floatOffset, query, queryLength);
        partial->compared += 1;
        if (!useThreshold || score >= threshold) {
            partial->heap.offer(score, i);
        }
    }
}

int chooseThreadCount(int count) {
    if (count < kMinParallelVectors) return 1;
    const unsigned int hardware = std::thread::hardware_concurrency();
    int threads = hardware == 0 ? kMaxThreads : static_cast<int>(hardware);
    threads = std::min(threads, kMaxThreads);
    threads = std::min(threads, count);
    return std::max(threads, 1);
}

jlong packResult(int compared, int size) {
    return (static_cast<jlong>(compared) << 32) | static_cast<uint32_t>(size);
}

jlong searchTopKNative(
    JNIEnv* env,
    jobject,
    jfloatArray vectorsArray,
    jintArray vectorFloatOffsetsArray,
    jintArray dimsArray,
    jfloatArray queryArray,
    jfloat threshold,
    jint topK,
    jfloatArray outScoresArray,
    jintArray outIndexesArray
) {
    if (vectorsArray == nullptr || vectorFloatOffsetsArray == nullptr || dimsArray == nullptr ||
        queryArray == nullptr || outScoresArray == nullptr || outIndexesArray == nullptr || topK <= 0) {
        return -1;
    }

    const int vectorLength = env->GetArrayLength(vectorsArray);
    const int count = env->GetArrayLength(vectorFloatOffsetsArray);
    const int dimsLength = env->GetArrayLength(dimsArray);
    const int queryLength = env->GetArrayLength(queryArray);
    const int outScoresLength = env->GetArrayLength(outScoresArray);
    const int outIndexesLength = env->GetArrayLength(outIndexesArray);
    if (count <= 0 || dimsLength < count || queryLength <= 0 ||
        outScoresLength < topK || outIndexesLength < topK) {
        return packResult(0, 0);
    }

    jboolean offsetsCopied = JNI_FALSE;
    jboolean dimsCopied = JNI_FALSE;
    jboolean queryCopied = JNI_FALSE;
    auto* vectorFloatOffsets = env->GetIntArrayElements(vectorFloatOffsetsArray, &offsetsCopied);
    auto* dims = env->GetIntArrayElements(dimsArray, &dimsCopied);
    auto* query = env->GetFloatArrayElements(queryArray, &queryCopied);
    if (vectorFloatOffsets == nullptr || dims == nullptr || query == nullptr) {
        if (query != nullptr) env->ReleaseFloatArrayElements(queryArray, query, JNI_ABORT);
        if (dims != nullptr) env->ReleaseIntArrayElements(dimsArray, dims, JNI_ABORT);
        if (vectorFloatOffsets != nullptr) {
            env->ReleaseIntArrayElements(vectorFloatOffsetsArray, vectorFloatOffsets, JNI_ABORT);
        }
        return -1;
    }

    const int limit = std::max(1, static_cast<int>(topK));
    const int threadCount = chooseThreadCount(count);
    std::vector<SearchPartial> partials;
    partials.reserve(threadCount);
    for (int i = 0; i < threadCount; ++i) {
        partials.emplace_back(limit);
    }
    std::vector<std::thread> workers;
    if (threadCount > 1) {
        workers.reserve(threadCount);
    }

    auto* vectors = static_cast<float*>(env->GetPrimitiveArrayCritical(vectorsArray, nullptr));
    if (vectors == nullptr) {
        env->ReleaseFloatArrayElements(queryArray, query, JNI_ABORT);
        env->ReleaseIntArrayElements(dimsArray, dims, JNI_ABORT);
        env->ReleaseIntArrayElements(vectorFloatOffsetsArray, vectorFloatOffsets, JNI_ABORT);
        return -1;
    }

    const int chunkSize = (count + threadCount - 1) / threadCount;
    if (threadCount == 1) {
        searchRange(
            vectors,
            vectorLength,
            vectorFloatOffsets,
            dims,
            query,
            queryLength,
            threshold,
            0,
            count,
            &partials[0]
        );
    } else {
        for (int threadIndex = 0; threadIndex < threadCount; ++threadIndex) {
            const int start = threadIndex * chunkSize;
            const int end = std::min(start + chunkSize, count);
            workers.emplace_back([&, start, end, threadIndex] {
                if (start < end) {
                    searchRange(
                        vectors,
                        vectorLength,
                        vectorFloatOffsets,
                        dims,
                        query,
                        queryLength,
                        threshold,
                        start,
                        end,
                        &partials[threadIndex]
                    );
                }
            });
        }
        for (auto& worker : workers) {
            worker.join();
        }
    }

    env->ReleasePrimitiveArrayCritical(vectorsArray, vectors, JNI_ABORT);
    env->ReleaseFloatArrayElements(queryArray, query, JNI_ABORT);
    env->ReleaseIntArrayElements(dimsArray, dims, JNI_ABORT);
    env->ReleaseIntArrayElements(vectorFloatOffsetsArray, vectorFloatOffsets, JNI_ABORT);

    TopKHeap merged(limit);
    int compared = 0;
    for (const SearchPartial& partial : partials) {
        compared += partial.compared;
        for (int i = 0; i < partial.heap.size; ++i) {
            merged.offer(partial.heap.scores[i], partial.heap.indexes[i]);
        }
    }
    merged.sortDescending();
    if (merged.size > 0) {
        env->SetFloatArrayRegion(outScoresArray, 0, merged.size, merged.scores.data());
        env->SetIntArrayRegion(outIndexesArray, 0, merged.size, merged.indexes.data());
    }
    return packResult(compared, merged.size);
}

} // namespace

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    jclass clazz = env->FindClass("com/photosearch/app/search/NativeVectorSearch");
    if (clazz == nullptr) {
        return JNI_ERR;
    }
    JNINativeMethod methods[] = {
        {
            const_cast<char*>("searchTopKNative"),
            const_cast<char*>("([F[I[I[FFI[F[I)J"),
            reinterpret_cast<void*>(searchTopKNative)
        },
    };
    if (env->RegisterNatives(clazz, methods, 1) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
