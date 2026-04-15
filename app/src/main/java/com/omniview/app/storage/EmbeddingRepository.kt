package com.omniview.app.storage

import android.util.Log

/**
 * Thin repository layer over [EmbeddingDao].
 * Provides logging and a single insertion point for embedding vectors.
 */
class EmbeddingRepository(private val dao: EmbeddingDao) {

    companion object {
        private const val TAG = "OmniView:EmbeddingDB"
    }

    suspend fun insert(entity: EmbeddingEntity) {
        dao.insert(entity)
        Log.d(TAG, "Inserted embedding for context #${entity.contextId} (app=${entity.app}, dim=${entity.embeddingDim})")
    }

    suspend fun insertAll(entities: List<EmbeddingEntity>) {
        if (entities.isEmpty()) return
        dao.insertAll(entities)
        Log.d(TAG, "Inserted ${entities.size} embeddings")
    }

    suspend fun count(): Int = dao.count()

    suspend fun getAll(): List<EmbeddingEntity> = dao.getAll()

    suspend fun getRecent(limit: Int = 100): List<EmbeddingEntity> = dao.getRecent(limit)

    suspend fun getByApp(app: String): List<EmbeddingEntity> = dao.getByApp(app)

    suspend fun getAllContextIds(): List<Int> = dao.getAllContextIds()
}
