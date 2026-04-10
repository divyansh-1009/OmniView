package com.omniview.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omniview.app.intelligence.EmbeddingEngine
import com.omniview.app.intelligence.EmbeddingWorker
import com.omniview.app.intelligence.LlmQaEngine
import com.omniview.app.intelligence.Tokenizer
import com.omniview.app.storage.SearchResult
import com.omniview.app.storage.VectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecallViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val vectorStore = VectorStore(application)
    private val llmQaEngine = LlmQaEngine(application)

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results

    private val _answer = MutableStateFlow("")
    val answer: StateFlow<String> = _answer

    private val _status = MutableStateFlow("Ready")
    val status: StateFlow<String> = _status

    init {
        EmbeddingWorker.schedulePeriodic(application)
    }

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                _status.value = "Searching..."
                val queryVector = embedQuery(trimmed)
                val hits = vectorStore.search(queryVector, topK = 8)
                _results.value = hits
                _status.value = "Found ${hits.size} result(s)"
            }.onFailure { error ->
                _status.value = error.message ?: "Search failed"
            }
        }
    }

    fun ask(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                _status.value = "Retrieving context..."
                _answer.value = ""

                val queryVector = embedQuery(trimmed)
                val hits = vectorStore.search(queryVector, topK = 5)
                _results.value = hits

                _status.value = "Generating answer..."
                llmQaEngine.answer(trimmed, hits.map { it.rawText }) { token ->
                    _answer.update { current -> current + token }
                }
                _status.value = "Done"
            }.onFailure { error ->
                _status.value = error.message ?: "Question answering failed"
            }
        }
    }

    private fun embedQuery(query: String): FloatArray {
        Tokenizer(app).use { tokenizer ->
            EmbeddingEngine(app).use { embeddingEngine ->
                return embeddingEngine.embed(tokenizer.encodeQuery(query))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        vectorStore.close()
    }
}
