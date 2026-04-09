package com.example.omniview.embedding

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Central coordinator for Steps 6 – 10 of the embedding pipeline.
 *
 * Call [init] once from [com.example.omniview.OmniViewApplication.onCreate].
 * All public methods are safe to call if assets are missing — they log a warning
 * and become no-ops so the rest of the app continues unaffected.
 *
 * ─── Flow ───────────────────────────────────────────────────────────────────
 *  Caller (A11y / OcrWorker)
 *    └─ enqueue(PendingEmbedItem)  →  EmbeddingQueue  →  EmbeddingWorkScheduler
 *
 *  EmbeddingWorker (charging, WorkManager)
 *    └─ processAll()
 *         ├─ mergeText()       Step 6
 *         ├─ tokenize()        Step 7
 *         ├─ generateEmbedding Step 7
 *         ├─ storeEmbedding()  Step 8
 *         └─ cleanup()         Step 9
 *
 *  UI / any caller
 *    └─ search(queryText)      Step 10
 * ────────────────────────────────────────────────────────────────────────────
 */
object PipelineManager {

    private const val TAG = "OmniView:Pipeline"

    private lateinit var appContext: Context
    private lateinit var repository: EmbeddingRepository

    private var tokenizer: BertTokenizer? = null
    private var mobileBert: MobileBertEmbedder? = null

    fun init(context: Context) {
        appContext  = context.applicationContext
        repository  = EmbeddingRepository(ObjectBoxStore.store)

        try {
            tokenizer  = BertTokenizer(context.assets.open("vocab.txt"))
            mobileBert = MobileBertEmbedder(context)
        } catch (e: Exception) {
            Log.w(TAG, "Model assets missing — pipeline disabled. " +
                    "Add mobilebert.tflite + vocab.txt to app/src/main/assets/", e)
        }
    }

    // ── Step 6 — Data merging ─────────────────────────────────────────────────

    fun mergeText(accessText: String?, ocrText: String?): String {
        val a = accessText ?: ""
        val o = ocrText    ?: ""
        return "$a $o".trim()
    }

    // ── Queueing (called by A11y service & OcrWorker) ─────────────────────────

    /**
     * Adds [item] to the persistent queue and triggers an [EmbeddingWorker]
     * via WorkManager.  The worker will only process when the device is charging.
     */
    fun enqueue(item: PendingEmbedItem) {
        EmbeddingQueue.enqueue(appContext, item)
        EmbeddingWorkScheduler.schedule(appContext)
    }

    // ── Steps 7–9 — Full pipeline (called by EmbeddingWorker) ────────────────

    /**
     * Drains the queue and processes every pending item.
     * Runs on [Dispatchers.Default] — safe to call from a CoroutineWorker.
     */
    suspend fun processAll() = withContext(Dispatchers.Default) {
        val tok = tokenizer
        val bert = mobileBert
        if (tok == null || bert == null) {
            Log.w(TAG, "Pipeline not initialised — skipping processAll()")
            return@withContext
        }

        val items = EmbeddingQueue.drainAll(appContext)
        if (items.isEmpty()) {
            Log.i(TAG, "Embedding queue empty — nothing to process")
            return@withContext
        }

        Log.i(TAG, "Processing ${items.size} pending embedding items")
        var stored = 0
        for (item in items) {
            if (processItem(item, tok, bert)) stored++
        }
        Log.i(TAG, "Embedding batch complete — $stored / ${items.size} items stored")
    }

    // ── Step 10 — Semantic search ─────────────────────────────────────────────

    /**
     * Embeds [queryText] on the fly and returns the 10 nearest stored embeddings.
     * Returns an empty list when the pipeline is disabled.
     */
    suspend fun search(
        queryText: String,
        maxResults: Int = 10
    ): List<EmbeddingEntity> = withContext(Dispatchers.Default) {
        val tok  = tokenizer  ?: return@withContext emptyList()
        val bert = mobileBert ?: return@withContext emptyList()

        val tokens        = tok.tokenize(queryText)
        val queryEmbedding = bert.generateEmbedding(tokens.inputIds, tokens.attentionMask)

        repository.findNearest(queryEmbedding, maxResults)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Returns true if an embedding was successfully stored. */
    private fun processItem(
        item: PendingEmbedItem,
        tok: BertTokenizer,
        bert: MobileBertEmbedder
    ): Boolean {
        // Step 6 — merge
        val text = mergeText(item.accessText, item.ocrText)
        if (text.isBlank()) return false

        // Step 7 — tokenize + embed
        val tokens    = tok.tokenize(text)
        val embedding = bert.generateEmbedding(tokens.inputIds, tokens.attentionMask)

        // Step 8 — store (timestamp | appName | embedding only)
        storeEmbedding(embedding, item.appName, item.timestamp)

        // Step 9 — delete screenshot
        cleanup(item.screenshotPath)

        return true
    }

    /** Step 8 — persists only timestamp, appName, embedding[512]. */
    private fun storeEmbedding(embedding: FloatArray, appName: String, timestamp: Long) {
        val entity = EmbeddingEntity(
            timestamp = timestamp,
            appName   = appName,
            embedding = embedding
        )
        repository.insert(entity)
        Log.d(TAG, "Stored embedding for app=$appName ts=$timestamp")
    }

    /**
     * Step 9 — deletes the source screenshot.
     *
     * Strings (accessText / ocrText) are intentionally NOT stored; they exist
     * only in local variables and will be GC'd when this scope exits.
     * File / URI deletion is the only action needed here.
     */
    private fun cleanup(screenshotPath: String?) {
        screenshotPath ?: return
        try {
            if (screenshotPath.startsWith("content://")) {
                appContext.contentResolver.delete(Uri.parse(screenshotPath), null, null)
            } else {
                File(screenshotPath).delete()
            }
            Log.d(TAG, "Deleted screenshot: $screenshotPath")
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete screenshot: $screenshotPath", e)
        }
    }
}
