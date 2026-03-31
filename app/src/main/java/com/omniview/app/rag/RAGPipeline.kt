package com.omniview.app.rag

import android.util.Log
import com.omniview.app.intelligence.EmbeddingEngine
import com.omniview.app.search.SearchResult
import com.omniview.app.search.SemanticSearch
import com.omniview.app.storage.EmbeddingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Orchestrates the full Retrieval-Augmented Generation pipeline:
 *
 *   1. [SemanticSearch] — embeds the query, retrieves top-K context chunks.
 *   2. Prompt assembly — wraps chunks + query in Gemma's chat template.
 *   3. [LlamaEngine.generate] — streams LLM response tokens via Flow.
 *
 * Usage:
 *   val pipeline = RAGPipeline(embeddingEngine, llamaEngine)
 *   pipeline.query("What was I doing in Chrome yesterday?", allEmbeddings)
 *       .collect { token -> /* append to UI */ }
 */
class RAGPipeline(
    embeddingEngine: EmbeddingEngine,
    private val llamaEngine: LlamaEngine
) {

    companion object {
        private const val TAG = "OmniView:RAG"
        private const val TOP_K = 5
        private const val MAX_CHUNK_LEN = 300 // chars per retrieved chunk in prompt
    }

    private val searcher = SemanticSearch(embeddingEngine)

    /**
     * Runs the RAG pipeline and returns a pair of:
     *  - The retrieved [SearchResult] list (for UI source cards).
     *  - A [Flow<String>] of LLM tokens streamed in real-time.
     */
    suspend fun query(
        userQuery: String,
        allEmbeddings: List<EmbeddingEntity>
    ): RAGResult {
        // Step 1: Semantic retrieval
        val retrieved = searcher.search(userQuery, allEmbeddings, topK = TOP_K)
        Log.i(TAG, "Retrieved ${retrieved.size} chunks for query: \"${userQuery.take(60)}\"")

        // Step 2: Build Gemma 3 instruct prompt
        val prompt = buildGemmaPrompt(userQuery, retrieved)
        Log.d(TAG, "Assembled prompt (${prompt.length} chars)")

        // Step 3: Stream LLM generation
        val tokenFlow: Flow<String> = if (llamaEngine.isReady()) {
            llamaEngine.generate(prompt)
        } else {
            flow { emit("[Error: LLM not loaded]") }
        }

        return RAGResult(retrieved, tokenFlow)
    }

    // ── Prompt Assembly ──────────────────────────────────────────────────

    /**
     * Assembles a Gemma 3 instruction-tuned prompt with the retrieved context
     * injected as a "system context" block before the user question.
     *
     * Gemma 3 chat template:
     *   <start_of_turn>user\n{content}<end_of_turn>\n<start_of_turn>model\n
     */
    private fun buildGemmaPrompt(
        query: String,
        chunks: List<SearchResult>
    ): String {
        val contextBlock = if (chunks.isEmpty()) {
            "No relevant context found in your screen history."
        } else {
            chunks.mapIndexed { i, result ->
                val snippet = result.text.take(MAX_CHUNK_LEN)
                    .replace('\n', ' ')
                    .trim()
                val timeStr = android.text.format.DateFormat
                    .format("MMM d, h:mm a", result.timestamp)
                "[${i + 1}] (${result.app}, $timeStr, score=${String.format("%.2f", result.score)}):\n$snippet"
            }.joinToString("\n\n")
        }

        return buildString {
            append("<start_of_turn>user\n")
            append("You are a helpful assistant with access to the user's recent screen activity.\n")
            append("Answer the question concisely using only the context below.\n")
            append("If the context doesn't contain the answer, say you don't know.\n\n")
            append("=== Context from screen history ===\n")
            append(contextBlock)
            append("\n\n=== Question ===\n")
            append(query)
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
    }
}

/** Wraps retrieved context chunks and the streaming LLM response. */
data class RAGResult(
    val retrievedChunks: List<SearchResult>,
    val tokenFlow: Flow<String>
)
