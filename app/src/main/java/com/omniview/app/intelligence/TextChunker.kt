package com.omniview.app.intelligence

object TextChunker {

    fun chunk(
        text: String,
        maxTokens: Int = 512,
        overlapTokens: Int = 50
    ): List<String> {
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(overlapTokens >= 0) { "overlapTokens must be non-negative" }
        require(overlapTokens < maxTokens) { "overlapTokens must be smaller than maxTokens" }

        val tokens = text
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        var start = 0
        val stride = maxTokens - overlapTokens

        while (start < tokens.size) {
            val end = minOf(start + maxTokens, tokens.size)
            chunks += tokens.subList(start, end).joinToString(" ")
            if (end == tokens.size) break
            start += stride
        }

        return chunks
    }
}
