package com.omniview.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ContextEntity::class, EmbeddingEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ContextDatabase : RoomDatabase() {

    abstract fun contextDao(): ContextDao
    abstract fun embeddingDao(): EmbeddingDao

    companion object {
        @Volatile private var INSTANCE: ContextDatabase? = null

        fun getInstance(context: Context): ContextDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ContextDatabase::class.java,
                    "omniview.db"
                )
                    // Dev/testing safety: allow DB reset on schema changes.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
