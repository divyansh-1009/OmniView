package com.example.omniview.embedding

import android.util.Log
import io.objectbox.BoxStore
import io.objectbox.Property
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.query
import io.objectbox.query.QueryBuilder

/**
 * ObjectBox read/write operations for [EmbeddingEntity].
 *
 * Uses the [io.objectbox.kotlin.query] extension (box.query { block }) which is
 * the ObjectBox 4.x recommended Kotlin API.  The extension returns [io.objectbox.query.Query]
 * directly (already built), so no .build() call is needed and the deprecated
 * no-arg Box.query() method is never called directly from our code.
 */
class EmbeddingRepository(store: BoxStore) {

    companion object {
        private const val TAG = "OmniView:EmbedRepo"
    }

    private val box = store.boxFor<EmbeddingEntity>()
    private val embeddingProp by lazy { ObjectBoxMeta.embeddingProperty() }
    private val timestampProp by lazy { ObjectBoxMeta.timestampProperty() }
    private val appNameProp by lazy { ObjectBoxMeta.appNameProperty() }

    fun insert(entity: EmbeddingEntity): Long = box.put(entity)

    fun count(): Long = box.count()

    /**
     * Returns the [maxResults] most similar stored embeddings to [queryVector]
     * using the HNSW index on [EmbeddingEntity.embedding].
     */
    fun findNearest(queryVector: FloatArray, maxResults: Int = 10): List<EmbeddingEntity> =
        box.query {
            nearestNeighbors(embeddingProp, queryVector, maxResults)
        }.find()

    fun getRecent(limit: Int = 100): List<EmbeddingEntity> =
        box.query {
            orderDesc(timestampProp)
        }.find(0, limit.toLong())

    fun getByApp(appName: String, limit: Int = 50): List<EmbeddingEntity> =
        box.query {
            equal(appNameProp, appName, QueryBuilder.StringOrder.CASE_INSENSITIVE)
        }.find(0, limit.toLong())

    fun deleteAll() {
        box.removeAll()
        Log.d(TAG, "All embeddings deleted")
    }
}

private object ObjectBoxMeta {
    private const val GENERATED_META_CLASS = "com.example.omniview.embedding.EmbeddingEntity_"

    fun embeddingProperty(): Property<EmbeddingEntity> = resolveProperty("embedding")
    fun timestampProperty(): Property<EmbeddingEntity> = resolveProperty("timestamp")
    fun appNameProperty(): Property<EmbeddingEntity> = resolveProperty("appName")

    @Suppress("UNCHECKED_CAST")
    private fun resolveProperty(fieldName: String): Property<EmbeddingEntity> {
        val metaClass = try {
            Class.forName(GENERATED_META_CLASS)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "ObjectBox meta class not found. Rebuild project to generate EmbeddingEntity_.",
                e
            )
        }
        return try {
            metaClass.getField(fieldName).get(null) as Property<EmbeddingEntity>
        } catch (e: Throwable) {
            throw IllegalStateException(
                "ObjectBox property '$fieldName' not found in EmbeddingEntity_.",
                e
            )
        }
    }
}
