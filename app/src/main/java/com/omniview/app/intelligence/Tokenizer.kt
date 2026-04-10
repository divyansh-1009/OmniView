package com.omniview.app.intelligence

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Pure-Kotlin WordPiece tokenizer — no DJL / SentencePiece dependency.
 *
 * Expects a standard BERT-style `vocab.txt` at:
 *   `<filesDir>/models/vocab.txt`
 *
 * One token per line, in vocab-index order. Special tokens
 * (`[PAD]`, `[UNK]`, `[CLS]`, `[SEP]`, `[MASK]`) must be present
 * at their standard BERT index positions.
 *
 * If the vocab file is not found a FileNotFoundException is thrown so the
 * EmbeddingWorker can surface a meaningful error and retry.
 */

/**
 * Holds fixed-length token IDs and their corresponding attention mask for one
 * encoded sequence. Both arrays are exactly EmbeddingEngine.MAX_SEQUENCE_LENGTH
 * elements long; padding positions carry 0 in both arrays.
 */
data class TokenizedInput(
    val tokenIds: IntArray,
    val attentionMask: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenizedInput) return false
        return tokenIds.contentEquals(other.tokenIds) &&
               attentionMask.contentEquals(other.attentionMask)
    }
    override fun hashCode(): Int {
        var result = tokenIds.contentHashCode()
        result = 31 * result + attentionMask.contentHashCode()
        return result
    }
}

class Tokenizer(
    private val context: Context,
    private val maxSequenceLength: Int = EmbeddingEngine.MAX_SEQUENCE_LENGTH
) : Closeable {

    /** token → id */
    private val vocab: Map<String, Int>
    /** Reverse: id → token (debug only) */
    private val ivocab: Array<String>

    private val clsId: Int
    private val sepId: Int
    private val padId: Int
    private val unkId: Int

    init {
        val stream: InputStream = openVocabStream(context)
        val lines = stream.bufferedReader().readLines()
        stream.close()

        val map = HashMap<String, Int>(lines.size * 2)
        lines.forEachIndexed { index, token -> map[token.trim()] = index }
        vocab = map
        ivocab = Array(lines.size) { lines[it].trim() }

        clsId = map["[CLS]"] ?: 101
        sepId = map["[SEP]"] ?: 102
        padId = map["[PAD]"] ?: 0
        unkId = map["[UNK]"] ?: 100

        Log.i(TAG, "Vocab loaded: ${vocab.size} tokens")
    }

    /**
     * Opens the vocab stream: bundled asset first, then filesDir runtime override.
     *
     * Priority:
     *  1. `assets/embedding_model/vocab.txt` (ships with APK — preferred)
     *  2. `<filesDir>/models/vocab.txt`       (runtime hot-swap / download)
     */
    private fun openVocabStream(ctx: Context): InputStream {
        try {
            return ctx.assets.open("embedding_model/vocab.txt").also {
                Log.i(TAG, "Vocab: loading from bundled asset")
            }
        } catch (_: Exception) { /* not in assets, fall through */ }

        val override = File(ctx.filesDir, "models/vocab.txt")
        if (override.exists()) {
            Log.i(TAG, "Vocab: loading from filesDir override")
            return override.inputStream()
        }

        throw FileNotFoundException(
            "vocab.txt not found.\n" +
            "Add vocab.txt (BERT base-uncased) to: app/src/main/assets/embedding_model/vocab.txt"
        )
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Encodes with the E5-style "passage: " prefix for corpus passages. */
    fun encodePassage(text: String): TokenizedInput = encode("passage: $text")

    /** Encodes with the E5-style "query: " prefix for search queries. */
    fun encodeQuery(text: String): TokenizedInput = encode("query: $text")

    /**
     * Encodes [text] into a fixed-length [TokenizedInput] of size [maxSequenceLength].
     *
     * Layout: [CLS] token₁ … tokenN [SEP] [PAD]…
     *
     * Long texts are truncated to fit the CLS + tokens + SEP budget.
     */
    fun encode(text: String): TokenizedInput {
        val ids  = IntArray(maxSequenceLength) { padId }
        val mask = IntArray(maxSequenceLength)

        // Tokenise the raw text
        val pieces = wordPieceTokenize(text)

        // Budget: CLS + up to (maxLen – 2) tokens + SEP
        val budget = maxSequenceLength - 2
        val truncated = if (pieces.size > budget) pieces.subList(0, budget) else pieces

        var pos = 0
        ids[pos] = clsId; mask[pos] = 1; pos++

        for (token in truncated) {
            ids[pos] = vocab[token] ?: unkId
            mask[pos] = 1
            pos++
        }

        ids[pos] = sepId; mask[pos] = 1

        return TokenizedInput(tokenIds = ids, attentionMask = mask)
    }

    /** No-op — pure Kotlin, nothing to release. */
    override fun close() = Unit

    // ── WordPiece implementation ───────────────────────────────────────────────

    /**
     * Standard WordPiece tokenization:
     *   1. Lowercase + basic whitespace split into "words"
     *   2. For each word, greedily match the longest vocab prefix; mark
     *      continuations with the "##" prefix.
     *   3. If any sub-word cannot be matched, the whole word → [UNK].
     */
    private fun wordPieceTokenize(text: String): List<String> {
        val result = mutableListOf<String>()

        // Basic whitespace / punctuation split (mirrors BERT BasicTokenizer)
        for (word in basicTokenize(text)) {
            val subTokens = greedyWordPiece(word)
            result.addAll(subTokens)
        }
        return result
    }

    /** Lowercases, tokenizes on whitespace and around punctuation. */
    private fun basicTokenize(text: String): List<String> {
        val cleaned = text.lowercase()
            .map { ch ->
                when {
                    ch == '\u0000' || ch == '\ufffd' -> ' '
                    isPunctuation(ch) -> " $ch "
                    isWhitespace(ch) -> ' '
                    else -> ch
                }
            }
            .joinToString("")

        return cleaned.trim().split(' ').filter { it.isNotEmpty() }
    }

    /** Greedy longest-match WordPiece for a single word. */
    private fun greedyWordPiece(word: String): List<String> {
        if (word.length > MAX_WORD_LEN) return listOf("[UNK]")

        val tokens = mutableListOf<String>()
        var start = 0

        while (start < word.length) {
            var end = word.length
            var found: String? = null

            // Try longest sub-string first
            while (start < end) {
                val sub = if (start == 0) word.substring(start, end)
                          else            "##" + word.substring(start, end)
                if (vocab.containsKey(sub)) {
                    found = sub
                    break
                }
                end--
            }

            if (found == null) {
                // No match for any suffix starting here → entire word is UNK
                return listOf("[UNK]")
            }

            tokens.add(found)
            start = end
        }

        return tokens
    }

    // ── Character category helpers ─────────────────────────────────────────────

    private fun isWhitespace(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' ||
        Character.getType(ch) == Character.SPACE_SEPARATOR.toInt()

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        // ASCII punctuation ranges
        if ((cp in 33..47) || (cp in 58..64) ||
            (cp in 91..96) || (cp in 123..126)) return true
        return Character.getType(ch) == Character.OTHER_PUNCTUATION.toInt()
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "OmniView:Tokenizer"

        /** Words longer than this are replaced with [UNK] without sub-word splitting. */
        private const val MAX_WORD_LEN = 200
    }
}
