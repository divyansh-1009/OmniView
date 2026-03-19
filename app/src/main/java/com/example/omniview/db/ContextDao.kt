package com.example.omniview.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ContextDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<ContextEntity>)

    @Query("SELECT COUNT(*) FROM context_entries")
    suspend fun count(): Int

    @Query("SELECT * FROM context_entries ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<ContextEntity>

    @Query("SELECT * FROM context_entries WHERE app = :app ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByApp(app: String, limit: Int = 50): List<ContextEntity>

    @Query("SELECT * FROM context_entries WHERE source = :source ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getBySource(source: String, limit: Int = 100): List<ContextEntity>

    @Query("DELETE FROM context_entries")
    suspend fun deleteAll()
}
