package com.omniview.app.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores a vector embedding generated from extracted screen context.
 *
 * Each row links back to a context_entries row via [contextId] and holds
 * the 512-dimensional embedding vector as a JSON-serialised FloatArray.
 *
 * Indices on app and timestamp support efficient filtering in search results.
 */
@Entity(
    tableName = "embedding_entries",
    indices = [
        Index("contextId"),
        Index("app"),
        Index("timestamp")
    ]
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val contextId: Int,        // references context_entries.id
    val app: String,
    val text: String,          // original text (for display in search results)
    val embedding: String,     // JSON-serialised FloatArray: "[0.12, -0.34, ...]"
    val embeddingDim: Int,     // vector dimension (e.g. 512)
    val timestamp: Long
)
