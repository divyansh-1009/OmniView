package com.omniview.app.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.omniview.app.storage.ExtractedContext
import com.omniview.app.storage.RawContext

@Entity(
    tableName = "context_entries",
    indices = [
        Index("app"),
        Index("timestamp"),
    ]
)
data class ContextEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val app: String,
    val text: String,
    val timestamp: Long,
    val source: String       // "accessibility" | "ocr"
)

fun RawContext.toEntity() = ContextEntity(
    app = app,
    text = text,
    timestamp = timestamp,
    source = "accessibility"
)

fun ExtractedContext.toEntity() = ContextEntity(
    app = app,
    text = text,
    timestamp = timestamp,
    source = source
)
