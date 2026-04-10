package com.omniview.app.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.omniview.app.intelligence.EmbeddingEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class SearchResult(
    val id: Long,
    val timestamp: Long,
    val screenshotPath: String,
    val rawText: String,
    val chunkIndex: Int,
    val packageName: String,
    val distance: Float
)

class VectorStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    private var sqliteVecAvailable: Boolean? = null

    init {
        tryLoadSqliteVec()
    }

    override fun onCreate(db: SQLiteDatabase) {
        createBaseSchema(db)
        ensureVectorSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createBaseSchema(db)
            ensureVectorSchema(db)
        }
    }

    fun insert(
        timestamp: Long,
        screenshotPath: String,
        rawText: String,
        chunkIndex: Int,
        packageName: String,
        embedding: FloatArray
    ): Long {
        require(embedding.size == EmbeddingEngine.STORED_EMBEDDING_DIMENSIONS) {
            "Expected ${EmbeddingEngine.STORED_EMBEDDING_DIMENSIONS}-dim embedding, got ${embedding.size}"
        }

        val db = writableDatabase
        val vectorBytes = vectorToBytes(embedding)

        db.beginTransaction()
        return try {
            val momentId = db.insertOrThrow(
                MOMENTS_TABLE,
                null,
                ContentValues().apply {
                    put("timestamp", timestamp)
                    put("screenshot", screenshotPath)
                    put("raw_text", rawText)
                    put("chunk_index", chunkIndex)
                    put("package_name", packageName)
                }
            )

            db.execSQL(
                "INSERT INTO $VECTORS_TABLE(moment_id, embedding) VALUES (?, ?)",
                arrayOf(momentId, vectorBytes)
            )

            db.setTransactionSuccessful()
            momentId
        } finally {
            db.endTransaction()
        }
    }

    fun search(queryVector: FloatArray, topK: Int = 5): List<SearchResult> {
        require(queryVector.size == EmbeddingEngine.STORED_EMBEDDING_DIMENSIONS) {
            "Expected ${EmbeddingEngine.STORED_EMBEDDING_DIMENSIONS}-dim query vector, got ${queryVector.size}"
        }

        return if (isSqliteVecAvailable()) {
            searchWithSqliteVec(queryVector, topK).ifEmpty { searchInKotlin(queryVector, topK) }
        } else {
            searchInKotlin(queryVector, topK)
        }
    }

    fun isScreenshotHandled(screenshotPath: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM $PROCESSED_TABLE WHERE screenshot = ? LIMIT 1",
            arrayOf(screenshotPath)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    fun markScreenshotHandled(
        screenshotPath: String,
        timestamp: Long,
        status: String,
        packageName: String?
    ) {
        writableDatabase.insertWithOnConflict(
            PROCESSED_TABLE,
            null,
            ContentValues().apply {
                put("screenshot", screenshotPath)
                put("timestamp", timestamp)
                put("status", status)
                put("package_name", packageName)
                put("processed_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun searchWithSqliteVec(queryVector: FloatArray, topK: Int): List<SearchResult> {
        val hex = vectorToBytes(queryVector).toHex()
        val sql = """
            SELECT m.id, m.timestamp, m.screenshot, m.raw_text, m.chunk_index,
                   m.package_name, mv.distance
            FROM $VECTORS_TABLE mv
            JOIN $MOMENTS_TABLE m ON m.id = mv.moment_id
            WHERE mv.embedding MATCH x'$hex'
              AND k = $topK
            ORDER BY mv.distance
        """.trimIndent()

        return runCatching {
            readableDatabase.rawQuery(sql, null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            SearchResult(
                                id = cursor.getLong(0),
                                timestamp = cursor.getLong(1),
                                screenshotPath = cursor.getString(2),
                                rawText = cursor.getString(3),
                                chunkIndex = cursor.getInt(4),
                                packageName = cursor.getString(5),
                                distance = cursor.getFloat(6)
                            )
                        )
                    }
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "sqlite-vec search failed; falling back to Kotlin cosine search", error)
            emptyList()
        }
    }

    private fun searchInKotlin(queryVector: FloatArray, topK: Int): List<SearchResult> {
        val sql = """
            SELECT m.id, m.timestamp, m.screenshot, m.raw_text, m.chunk_index,
                   m.package_name, mv.embedding
            FROM $VECTORS_TABLE mv
            JOIN $MOMENTS_TABLE m ON m.id = mv.moment_id
        """.trimIndent()

        return readableDatabase.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val embedding = bytesToVector(cursor.getBlob(6))
                    val distance = cosineDistance(queryVector, embedding)
                    add(
                        SearchResult(
                            id = cursor.getLong(0),
                            timestamp = cursor.getLong(1),
                            screenshotPath = cursor.getString(2),
                            rawText = cursor.getString(3),
                            chunkIndex = cursor.getInt(4),
                            packageName = cursor.getString(5),
                            distance = distance
                        )
                    )
                }
            }
        }.sortedBy { it.distance }.take(topK)
    }

    private fun createBaseSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $MOMENTS_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                screenshot TEXT NOT NULL,
                raw_text TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                package_name TEXT NOT NULL DEFAULT 'unknown'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_moments_timestamp ON $MOMENTS_TABLE(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_moments_screenshot ON $MOMENTS_TABLE(screenshot)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $PROCESSED_TABLE (
                screenshot TEXT PRIMARY KEY,
                timestamp INTEGER NOT NULL,
                status TEXT NOT NULL,
                package_name TEXT,
                processed_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun ensureVectorSchema(db: SQLiteDatabase): Boolean {
        sqliteVecAvailable?.let { return it }

        val available = runCatching {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS $VECTORS_TABLE USING vec0(
                    moment_id INTEGER,
                    embedding FLOAT[${EmbeddingEngine.STORED_EMBEDDING_DIMENSIONS}]
                )
                """.trimIndent()
            )
            true
        }.getOrElse { error ->
            Log.w(TAG, "sqlite-vec virtual table unavailable; using regular BLOB table", error)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $VECTORS_TABLE (
                    moment_id INTEGER PRIMARY KEY,
                    embedding BLOB NOT NULL
                )
                """.trimIndent()
            )
            false
        }

        sqliteVecAvailable = available
        return available
    }

    private fun isSqliteVecAvailable(): Boolean {
        return ensureVectorSchema(writableDatabase)
    }

    private fun tryLoadSqliteVec() {
        for (libraryName in SQLITE_VEC_LIBRARY_CANDIDATES) {
            val loaded = runCatching {
                System.loadLibrary(libraryName)
                true
            }.getOrDefault(false)
            if (loaded) {
                Log.i(TAG, "Loaded sqlite-vec native library: $libraryName")
                return
            }
        }
    }

    companion object {
        private const val TAG = "OmniView:VectorStore"
        private const val DATABASE_NAME = "recall_vectors.db"
        private const val DATABASE_VERSION = 2
        private const val MOMENTS_TABLE = "moments"
        private const val VECTORS_TABLE = "moment_vectors"
        private const val PROCESSED_TABLE = "processed_screenshots"
        private val SQLITE_VEC_LIBRARY_CANDIDATES = listOf("sqlite_vec", "vec0")

        fun vectorToBytes(vector: FloatArray): ByteArray {
            val buffer = ByteBuffer
                .allocate(vector.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            vector.forEach(buffer::putFloat)
            return buffer.array()
        }

        fun bytesToVector(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
        }

        fun cosineDistance(left: FloatArray, right: FloatArray): Float {
            if (left.size != right.size) return Float.MAX_VALUE

            var dot = 0.0
            var leftNorm = 0.0
            var rightNorm = 0.0
            for (index in left.indices) {
                dot += left[index] * right[index]
                leftNorm += left[index] * left[index]
                rightNorm += right[index] * right[index]
            }

            val denominator = sqrt(leftNorm) * sqrt(rightNorm)
            if (denominator <= 0.0) return Float.MAX_VALUE
            return (1.0 - dot / denominator).toFloat()
        }

        private fun ByteArray.toHex(): String {
            return joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        }
    }
}
