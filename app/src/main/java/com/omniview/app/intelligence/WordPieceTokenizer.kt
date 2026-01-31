package com.omniview.app.intelligence

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Result of tokenising a text string for the embedding model.
 *
 * @param inputIds   Token ID sequence: [CLS] tokens... [SEP] [PAD]...
 * @param attentionMask  1 for real tokens, 0 for padding positions.
 */
data class TokenizedInput(
    val inputIds: IntArray,
    val attentionMask: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenizedInput) return false
        return inputIds.contentEquals(other.inputIds) &&
                attentionMask.contentEquals(other.attentionMask)
    }

    override fun hashCode(): Int {
        return 31 * inputIds.contentHashCode() + attentionMask.contentHashCode()
    }
}

/**
 * BERT-compatible WordPiece tokenizer backed by the provided `vocab.txt`.
 *
 * Implements the standard BERT tokenization pipeline:
 *   1. Lowercase + strip accents
 *   2. Split on whitespace
 *   3. Split on punctuation
 *   4. WordPiece lookup with "##" continuation prefix
 *   5. Wrap with [CLS], [SEP] and pad to [MAX_SEQ_LENGTH]
 *
 * Thread-safe after construction (vocabulary map is immutable).
 */
class WordPieceTokenizer(context: Context) {

    companion object {
        private const val TAG = "OmniView:Tokenizer"
        private const val VOCAB_FILE = "embeddingmodels/vocab.txt"
        const val MAX_SEQ_LENGTH = 128
        const val CLS_TOKEN_ID = 101  // line 102 in file but 0-indexed → id 101... 
        const val SEP_TOKEN_ID = 102
        const val PAD_TOKEN_ID = 0
        const val UNK_TOKEN_ID = 100
    }

    private val vocab: Map<String, Int>

    init {
        val vocabMap = LinkedHashMap<String, Int>(32_000)
        context.assets.open(VOCAB_FILE).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                var index = 0
                reader.forEachLine { line ->
                    vocabMap[line.trim()] = index
                    index++
                }
            }
        }
        vocab = vocabMap
        // Read actual special token IDs from the loaded vocab
        Log.i(TAG, "Vocabulary loaded: ${vocab.size} tokens. " +
                "[CLS]=${vocab["[CLS]"]}, [SEP]=${vocab["[SEP]"]}, " +
                "[PAD]=${vocab["[PAD]"]}, [UNK]=${vocab["[UNK]"]}")
    }

    /** Actual token IDs resolved from the loaded vocabulary */
    private val clsId get() = vocab["[CLS]"] ?: CLS_TOKEN_ID
    private val sepId get() = vocab["[SEP]"] ?: SEP_TOKEN_ID
    private val padId get() = vocab["[PAD]"] ?: PAD_TOKEN_ID
    private val unkId get() = vocab["[UNK]"] ?: UNK_TOKEN_ID

    /**
     * Tokenises [text] into model-ready input tensors.
     *
     * @return [TokenizedInput] with padded/truncated arrays of length [MAX_SEQ_LENGTH].
     */
    fun tokenize(text: String): TokenizedInput {
        val tokens = mutableListOf<Int>()
        tokens.add(clsId)

        val words = basicTokenize(text)
        for (word in words) {
            val subTokens = wordPieceTokenize(word)
            tokens.addAll(subTokens)
            // Leave room for [SEP]
            if (tokens.size >= MAX_SEQ_LENGTH - 1) break
        }

        // Truncate to make room for [SEP]
        while (tokens.size >= MAX_SEQ_LENGTH) {
            tokens.removeAt(tokens.size - 1)
        }
        tokens.add(sepId)

        val inputIds = IntArray(MAX_SEQ_LENGTH) { padId }
        val attentionMask = IntArray(MAX_SEQ_LENGTH) { 0 }
        for (i in tokens.indices) {
            inputIds[i] = tokens[i]
            attentionMask[i] = 1
        }

        return TokenizedInput(inputIds, attentionMask)
    }

    // ── BERT Basic Tokenization ─────────────────────────────────────────

    /**
     * Lowercases, strips accents, splits on whitespace and punctuation.
     * Returns a list of normalised word strings ready for WordPiece.
     */
    private fun basicTokenize(text: String): List<String> {
        val normalized = text.lowercase().trim()
        val result = mutableListOf<String>()
        val current = StringBuilder()

        for (ch in normalized) {
            when {
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                }
                isPunctuation(ch) -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                    result.add(ch.toString())
                }
                isAccent(ch) -> { /* strip accents */ }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    /**
     * Greedy longest-match WordPiece algorithm.
     * Prefixes continuation sub-tokens with "##".
     */
    private fun wordPieceTokenize(word: String): List<Int> {
        if (word.isEmpty()) return emptyList()

        val tokens = mutableListOf<Int>()
        var start = 0

        while (start < word.length) {
            var end = word.length
            var matched = false

            while (start < end) {
                val substr = if (start == 0) {
                    word.substring(start, end)
                } else {
                    "##" + word.substring(start, end)
                }
                val id = vocab[substr]
                if (id != null) {
                    tokens.add(id)
                    matched = true
                    start = end
                    break
                }
                end--
            }

            if (!matched) {
                tokens.add(unkId)
                start++
            }
        }
        return tokens
    }

    // ── Character classification helpers ────────────────────────────────

    private fun isPunctuation(ch: Char): Boolean {
        val code = ch.code
        // ASCII punctuation ranges
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        // Unicode general punctuation
        return Character.getType(ch).let {
            it == Character.CONNECTOR_PUNCTUATION.toInt() ||
            it == Character.DASH_PUNCTUATION.toInt() ||
            it == Character.START_PUNCTUATION.toInt() ||
            it == Character.END_PUNCTUATION.toInt() ||
            it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            it == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            it == Character.OTHER_PUNCTUATION.toInt()
        }
    }

    private fun isAccent(ch: Char): Boolean {
        return Character.getType(ch) == Character.NON_SPACING_MARK.toInt()
    }
}
