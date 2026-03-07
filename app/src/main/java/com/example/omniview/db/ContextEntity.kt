package com.example.omniview.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.omniview.model.ExtractedContext
import com.example.omniview.model.RawContext

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
