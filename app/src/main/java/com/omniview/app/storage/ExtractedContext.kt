package com.omniview.app.storage

data class ExtractedContext(
    val app: String,
    val text: String,
    val source: String,   // "accessibility" or "ocr"
    val timestamp: Long
)
