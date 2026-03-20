package com.omniview.app.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<EmbeddingEntity>)

    @Query("SELECT COUNT(*) FROM embedding_entries")
    suspend fun count(): Int

    @Query("SELECT * FROM embedding_entries ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<EmbeddingEntity>

    @Query("SELECT * FROM embedding_entries")
    suspend fun getAll(): List<EmbeddingEntity>

    @Query("SELECT * FROM embedding_entries WHERE app = :app")
    suspend fun getByApp(app: String): List<EmbeddingEntity>

    @Query("DELETE FROM embedding_entries")
    suspend fun deleteAll()

    /** Returns IDs of context entries that already have embeddings. */
    @Query("SELECT contextId FROM embedding_entries")
    suspend fun getAllContextIds(): List<Int>
}
