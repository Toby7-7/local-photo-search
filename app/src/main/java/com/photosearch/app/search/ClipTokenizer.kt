package com.photosearch.app.search

import com.photosearch.app.model.TokenizerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class ClipTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val ranks: Map<Pair<String, String>, Int>,
    private val config: TokenizerConfig
) {
    private val cache = LinkedHashMap<String, List<Int>>()
    private val byteEncoder = bytesToUnicode()
    private val pattern = Regex(
        "<\\|startoftext\\|>|<\\|endoftext\\|>|[\\p{L}]+|[\\p{N}]+|[^\\s\\p{L}\\p{N}]+",
        RegexOption.IGNORE_CASE
    )

    fun encode(text: String): LongArray {
        val tokens = ArrayList<Int>(config.maxLength)
        vocab[config.bosToken]?.let(tokens::add)
        pattern.findAll(text.lowercase()).forEach { match ->
            if (tokens.size >= config.maxLength - 1) return@forEach
            val piece = match.value
                .encodeToByteArray()
                .joinToString(separator = "") { byte -> byteEncoder[byte.toInt() and 0xFF].toString() }
            for (id in bpe(piece)) {
                if (tokens.size >= config.maxLength - 1) break
                tokens += id
            }
        }
        vocab[config.eosToken]?.let { eos ->
            if (tokens.size < config.maxLength) tokens += eos
        }
        while (tokens.size < config.maxLength) tokens += config.padTokenId
        return LongArray(config.maxLength) { tokens[it].toLong() }
    }

    private fun bpe(token: String): List<Int> {
        cache[token]?.let { return it }
        if (token.isEmpty()) return emptyList()
        var word = token.map { it.toString() }.toMutableList()
        word[word.lastIndex] = word.last() + "</w>"
        if (word.size == 1) {
            return listOfNotNull(vocab[word.first()]).also { cache[token] = it }
        }

        while (word.size > 1) {
            // Find the lowest-rank adjacent pair with a single linear scan. The previous
            // implementation rebuilt a PriorityQueue (and offered every adjacent pair)
            // on each merge round; BPE merge ranks are unique, so a min-scan yields the
            // identical pair without the per-round allocation churn the strategy doc
            // flagged as the Tokenizer bottleneck.
            var bestRank = Int.MAX_VALUE
            var bestIndex = -1
            for (i in 0 until word.lastIndex) {
                val rank = ranks[word[i] to word[i + 1]]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestIndex = i
                }
            }
            if (bestIndex < 0) break
            val first = word[bestIndex]
            val second = word[bestIndex + 1]
            val next = ArrayList<String>(word.size - 1)
            var i = 0
            while (i < word.size) {
                if (i < word.lastIndex && word[i] == first && word[i + 1] == second) {
                    next += first + second
                    i += 2
                } else {
                    next += word[i]
                    i++
                }
            }
            word = next
        }

        val ids = word.mapNotNull { vocab[it] }
        cache[token] = ids
        return ids
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromFiles(modelDir: File, config: TokenizerConfig): ClipTokenizer {
            config.tokenizerFileName?.let { tokenizerName ->
                val tokenizerFile = File(modelDir, tokenizerName)
                if (tokenizerFile.exists()) {
                    return fromTokenizerJson(tokenizerFile, config)
                }
            }
            val vocabFile = File(modelDir, requireNotNull(config.vocabFileName) { "Missing vocab file in tokenizer config" })
            val mergesFile = File(modelDir, requireNotNull(config.mergesFileName) { "Missing merges file in tokenizer config" })
            return ClipTokenizer(
                vocab = parseVocab(vocabFile),
                ranks = parseMerges(mergesFile),
                config = config
            )
        }

        private fun fromTokenizerJson(file: File, config: TokenizerConfig): ClipTokenizer {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val model = root["model"]?.jsonObject ?: error("tokenizer.json has no model")
            val vocab = model["vocab"]?.jsonObject
                ?.mapValues { it.value.jsonPrimitive.int }
                ?: error("tokenizer.json has no model.vocab")
            val merges = model["merges"]?.jsonArray ?: JsonArray(emptyList())
            val ranks = merges.mapIndexedNotNull { index, element ->
                val value = element.jsonPrimitive.content
                val pieces = value.split(' ')
                if (pieces.size == 2) (pieces[0] to pieces[1]) to index else null
            }.toMap()
            return ClipTokenizer(vocab, ranks, config)
        }

        private fun parseVocab(file: File): Map<String, Int> =
            json.parseToJsonElement(file.readText()).jsonObject
                .mapValues { it.value.jsonPrimitive.int }

        private fun parseMerges(file: File): Map<Pair<String, String>, Int> =
            file.readLines()
                .asSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapIndexedNotNull { index, line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size == 2) (parts[0] to parts[1]) to index else null
                }
                .toMap()

        private fun bytesToUnicode(): Map<Int, Char> {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code)
            bs += ('¡'.code..'¬'.code)
            bs += ('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs += b
                    cs += 256 + n
                    n++
                }
            }
            return bs.zip(cs.map { it.toChar() }).toMap()
        }
    }
}
