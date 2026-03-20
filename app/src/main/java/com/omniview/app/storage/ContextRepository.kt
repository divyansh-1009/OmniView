package com.omniview.app.storage

import android.util.Log

class ContextRepository(private val dao: ContextDao) {

    companion object {
        private const val TAG = "OmniView:DB"
    }

    suspend fun insertAll(entities: List<ContextEntity>) {
        if (entities.isEmpty()) return
        dao.insertAll(entities)
        Log.d(TAG, "Inserted ${entities.size} rows (sources: ${entities.groupBy { it.source }.mapValues { it.value.size }})")
    }

    suspend fun count(): Int = dao.count()

    suspend fun getRecent(limit: Int = 100): List<ContextEntity> = dao.getRecent(limit)

    suspend fun getByApp(app: String, limit: Int = 50): List<ContextEntity> = dao.getByApp(app, limit)
}
