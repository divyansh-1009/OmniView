package com.example.omniview.embedding

import java.io.InputStream

/**
 * Returned by [BertTokenizer.tokenize] — ready to feed straight into the TFLite
 * model as two separate input tensors.
 */
data class TokenizerResult(
    val inputIds: IntArray,        // [MAX_LEN]  int32
    val attentionMask: IntArray    // [MAX_LEN]  int32  (1 = real token, 0 = pad)
)

/**
 * Standalone BERT WordPiece tokeniser.
 *
 * Pipeline:
 *   1. Lowercase the input
 *   2. Split on whitespace
 *   3. Split each word at every punctuation boundary
 *   4. Greedy longest-match WordPiece decomposition with "##" continuation prefix
 *   5. Map subword strings → integer vocab IDs
 *   6. Wrap in [CLS] … [SEP] + pad to [MAX_LEN]
 *
 * Initialise once (vocab load is ~30 K lines) and reuse across calls.
 * Thread-safe: [tokenize] is pure and stateless after construction.
 */
class BertTokenizer(vocabStream: InputStream) {

    companion object {
        const val MAX_LEN = 128
        private const val CLS_ID = 101
        private const val SEP_ID = 102
        private const val PAD_ID = 0
        private const val MAX_CHARS_PER_WORD = 100
    }

    private val vocab: Map<String, Int> = loadVocab(vocabStream)
    private val unkId: Int = vocab["[UNK]"] ?: 100

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Tokenises [text] and returns two integer arrays padded to [MAX_LEN].
     *
     * Layout: [CLS] t₀ t₁ … tₙ [SEP] [PAD] … [PAD]
     */
    fun tokenize(text: String): TokenizerResult {
        val tokenIds = encode(text)

        val inputIds    = IntArray(MAX_LEN) { PAD_ID }
        val attentionMask = IntArray(MAX_LEN) { 0 }

        // [CLS]
        inputIds[0]     = CLS_ID
        attentionMask[0] = 1

        // Truncate to MAX_LEN − 2 (space for [CLS] + [SEP])
        val truncated = tokenIds.take(MAX_LEN - 2)
        truncated.forEachIndexed { i, id ->
            inputIds[i + 1]      = id
            attentionMask[i + 1] = 1
        }

        // [SEP]
        val sepIdx = truncated.size + 1
        inputIds[sepIdx]      = SEP_ID
        attentionMask[sepIdx] = 1

        return TokenizerResult(inputIds, attentionMask)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Lowercase → whitespace split → punctuation split → WordPiece → IDs. */
    private fun encode(text: String): List<Int> {
        val ids = mutableListOf<Int>()
        text.lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .flatMap(::splitOnPunctuation)
            .forEach { word ->
                wordPiece(word).forEach { piece -> ids += vocab[piece] ?: unkId }
            }
        return ids
    }

    /**
     * Inserts split boundaries around each punctuation character.
     * "hello," → ["hello", ","]
     */
    private fun splitOnPunctuation(word: String): List<String> {
        val parts = mutableListOf<String>()
        val buf   = StringBuilder()
        for (ch in word) {
            if (isPunctuation(ch)) {
                if (buf.isNotEmpty()) { parts += buf.toString(); buf.clear() }
                parts += ch.toString()
            } else {
                buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) parts += buf.toString()
        return parts
    }

    /**
     * Greedy longest-match left-to-right WordPiece.
     * "embedding" → ["em", "##bed", "##ding"]
     * Returns ["[UNK]"] when any position cannot be matched.
     */
    private fun wordPiece(word: String): List<String> {
        if (word.length > MAX_CHARS_PER_WORD) return listOf("[UNK]")
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end   = word.length
            var match: String? = null
            while (start < end) {
                val sub = if (start == 0) word.substring(start, end)
                           else "##${word.substring(start, end)}"
                if (vocab.containsKey(sub)) { match = sub; break }
                end--
            }
            if (match == null) return listOf("[UNK]")
            pieces += match
            start = end
        }
        return pieces
    }

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        // ASCII punctuation bands: ! to /,  : to @,  [ to `,  { to ~
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun loadVocab(stream: InputStream): Map<String, Int> {
        val map = HashMap<String, Int>(32_768)
        stream.bufferedReader().useLines { lines ->
            lines.forEachIndexed { idx, token -> map[token.trim()] = idx }
        }
        return map
    }
}
