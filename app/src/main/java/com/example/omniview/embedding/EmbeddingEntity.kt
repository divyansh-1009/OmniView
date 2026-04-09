package com.example.omniview.embedding

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id

/**
 * The only persisted output of the pipeline.
 *
 * Final schema: timestamp | appName | embedding[512]
 *
 * Raw text and screenshots are deliberately NOT stored (privacy-first design).
 * All semantic retrieval is performed via the HNSW vector index on [embedding].
 */
@Entity
data class EmbeddingEntity(

    @Id
    var id: Long = 0,

    var timestamp: Long = 0,

    var appName: String = "",

    @HnswIndex(dimensions = 512)
    var embedding: FloatArray = FloatArray(512)
) {
    // FloatArray breaks data-class equals/hashCode — provide stable overrides.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return id == other.id && timestamp == other.timestamp
    }

    override fun hashCode(): Int = id.hashCode()
}
