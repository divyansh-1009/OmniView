package com.example.omniview.processing

/**
 * Stateless text cleaner applied to every token before it enters the pipeline.
 *
 * Two entry points:
 *  - [cleanToken]  → single word / phrase (used by accessibility node traversal)
 *  - [cleanBlock]  → multi-line text block (used by OCR output)
 */
object ContextCleaner {

    private const val MIN_LENGTH = 5

    // Navigation chrome and status-bar labels that carry no semantic value.
    private val NOISE_EXACT = setOf(
        "navigate up", "back", "home", "recents", "overview",
        "more options", "menu", "close", "dismiss", "clear all",
        "notifications", "quick settings", "status bar",
        "wifi", "bluetooth", "airplane mode",
        "silent mode", "do not disturb",
        "battery", "charging",
        "search", "share",
    )

    // Structural patterns that are never meaningful content:
    //   • Clock time      → "6:28"  "00:09"  "03:40"  "11:59 PM"
    //   • Battery %       → "84%"
    //   • No letters      → "---"  "..."  "•"  (after lowercasing)
    private val NOISE_PATTERNS = listOf(
        Regex("""^\d{1,2}:\d{2}(:\d{2})?(\s*(am|pm))?$"""),
        Regex("""^\d+%$"""),
        Regex("""^[^a-z]+$"""),                 // no letters at all
    )

    /**
     * Clean a single token / short phrase.
     * Returns the normalised string, or null if it should be discarded.
     */
    fun cleanToken(raw: String): String? {
        val normalized = raw.trim().lowercase()

        if (normalized.length < MIN_LENGTH) return null
        if (normalized in NOISE_EXACT) return null
        if (NOISE_PATTERNS.any { it.matches(normalized) }) return null

        return normalized
    }

    /**
     * Clean a multi-line text block (e.g. an OCR text block).
     * Cleans each line individually and reassembles; returns null if nothing survives.
     */
    fun cleanBlock(raw: String): String? {
        val cleaned = raw.lines()
            .mapNotNull { cleanToken(it) }
            .distinct()
            .joinToString("\n")

        return cleaned.ifBlank { null }
    }
}
