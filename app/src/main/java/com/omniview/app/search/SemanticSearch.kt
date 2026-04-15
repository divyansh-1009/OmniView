package com.omniview.app.search

import android.util.Log
import com.omniview.app.intelligence.EmbeddingEngine
import com.omniview.app.storage.EmbeddingEntity

/**
 * A single semantic search result.
 *
 * @param text      The original context text that matched.
 * @param app       Package name of the source app.
 * @param timestamp Capture timestamp.
 * @param score     Cosine similarity to the query (0.0 – 1.0 for normalised vectors).
 */
data class SearchResult(
    val text: String,
    val app: String,
    val timestamp: Long,
    val score: Float
)

/**
 * Performs semantic search over stored embeddings using cosine similarity.
 *
 * Usage:
 *   1. Construct with an initialised [EmbeddingEngine]
 *   2. Call [search] with a natural-language query and all stored embeddings
 *   3. Receive top-K results ranked by relevance
 *
 * Since all stored embeddings are L2-normalised (by [EmbeddingEngine]),
 * cosine similarity reduces to a simple dot product.
 */
class SemanticSearch(private val embeddingEngine: EmbeddingEngine) {

    companion object {
        private const val TAG = "OmniView:Search"
    }

    /**
     * Embeds the [query], computes cosine similarity against all [allEmbeddings],
     * and returns the [topK] most similar results sorted by descending score.
     *
     * @param query          Natural-language search query.
     * @param allEmbeddings  All stored embedding entities to search against.
     * @param topK           Maximum number of results to return.
     * @return Sorted list of [SearchResult] with highest-similarity first.
     */
    fun search(
        query: String,
        allEmbeddings: List<EmbeddingEntity>,
        topK: Int = 10
    ): List<SearchResult> {
        if (allEmbeddings.isEmpty()) {
            Log.i(TAG, "No embeddings to search against")
            return emptyList()
        }

        Log.d(TAG, "Searching ${allEmbeddings.size} embeddings for query: \"${query.take(80)}\"")
        val startMs = System.currentTimeMillis()

        val queryEmbedding = embeddingEngine.generateEmbedding(query)

        val results = allEmbeddings.mapNotNull { entity ->
            val stored = deserializeEmbedding(entity.embedding)
            if (stored == null || stored.size != queryEmbedding.size) {
                Log.w(TAG, "Skipping malformed embedding (id=${entity.id})")
                return@mapNotNull null
            }
            val score = dotProduct(queryEmbedding, stored)
            SearchResult(
                text = entity.text,
                app = entity.app,
                timestamp = entity.timestamp,
                score = score
            )
        }
            .sortedByDescending { it.score }
            .take(topK)

        val elapsedMs = System.currentTimeMillis() - startMs
        Log.i(TAG, "Search complete in ${elapsedMs}ms: " +
                "${results.size} results (top score=${results.firstOrNull()?.score ?: 0f})")

        return results
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Deserialises a JSON-formatted FloatArray string like "[0.12, -0.34, ...]".
     */
    private fun deserializeEmbedding(json: String): FloatArray? {
        return try {
            val content = json.trim().removePrefix("[").removeSuffix("]")
            if (content.isBlank()) return null
            content.split(",")
                .map { it.trim().toFloat() }
                .toFloatArray()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize embedding", e)
            null
        }
    }

    /**
     * Dot product of two vectors.
     * For L2-normalised vectors, this equals cosine similarity.
     */
    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }
}
