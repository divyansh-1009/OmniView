package com.omniview.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import androidx.core.content.ContextCompat
import com.omniview.app.intelligence.EmbeddingEngine
import com.omniview.app.rag.LlamaEngine
import com.omniview.app.rag.RAGPipeline
import com.omniview.app.search.SearchResult
import com.omniview.app.storage.ContextDatabase
import com.omniview.app.storage.EmbeddingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.File

// ── UI State ─────────────────────────────────────────────────────────────────

enum class ModelStatus { NOT_LOADED, LOADING, READY, ERROR }

enum class Role { USER, AI }

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val isGenerating: Boolean = false,
    val retrievedChunks: List<SearchResult> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class RagUiState(
    val query: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val modelStatus: ModelStatus = ModelStatus.NOT_LOADED,
    val modelError: String? = null,
    val embeddingCount: Int = 0,
    val databaseSizeMb: Float = 0f
)



// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * Drives the Ask / RAG screen.
 *
 * Responsibilities:
 *  - Tracks model load status via [LlamaEngine].
 *  - Provides [loadModel] to initialise llama.cpp on a background thread.
 *  - Provides [submitQuery] which runs the full RAG pipeline and streams
 *    tokens into [uiState].answer.
 */
class RagViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OmniView:RagVM"
    }

    private val _uiState = MutableStateFlow(RagUiState())
    val uiState: StateFlow<RagUiState> = _uiState.asStateFlow()

    private val llamaEngine = LlamaEngine.getInstance()
    private val db = ContextDatabase.getInstance(application)
    private val embeddingRepo = EmbeddingRepository(db.embeddingDao())

    // Lazily created once the model is ready
    private var ragPipeline: RAGPipeline? = null
    private var generationJob: Job? = null

    init {
        // Refresh embedding count and DB size on start
        viewModelScope.launch(Dispatchers.IO) {
            val count = embeddingRepo.count()
            val sizeMb = calculateDatabaseSize()
            _uiState.update { it.copy(embeddingCount = count, databaseSizeMb = sizeMb) }
        }
        
        // Let the ViewModel observe the underlying engine's state directly
        viewModelScope.launch {
            llamaEngine.stateFlow.collect { engineState ->
                val uiStatus = when (engineState) {
                    LlamaEngine.State.NOT_LOADED -> ModelStatus.NOT_LOADED
                    LlamaEngine.State.LOADING -> ModelStatus.LOADING
                    LlamaEngine.State.READY -> ModelStatus.READY
                    LlamaEngine.State.ERROR -> ModelStatus.ERROR
                }
                _uiState.update { 
                    it.copy(
                        modelStatus = uiStatus, 
                        modelError = llamaEngine.errorMessage
                    ) 
                }
            }
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun setQuery(q: String) {
        _uiState.update { it.copy(query = q) }
    }

    /**
     * Loads the GGUF model within a Foreground Service.
     */
    fun loadModel() {
        if (llamaEngine.isReady()) return

        val app = getApplication<Application>()
        val intent = Intent(app, com.omniview.app.rag.LlamaService::class.java).apply {
            action = com.omniview.app.rag.LlamaService.ACTION_START_AND_LOAD
        }
        ContextCompat.startForegroundService(app, intent)
    }

    /**
     * Unloads the model and stops the Foreground Service to free memory.
     */
    fun unloadModel() {
        val app = getApplication<Application>()
        val intent = Intent(app, com.omniview.app.rag.LlamaService::class.java).apply {
            action = com.omniview.app.rag.LlamaService.ACTION_STOP
        }
        app.startService(intent) // A simple startService suffices to deliver the STOP action
    }

    /**
     * Cancels any in-flight generation and runs a new RAG query.
     */
    fun submitQuery() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return
        if (!llamaEngine.isReady()) {
            _uiState.update { it.copy(modelError = "Model not loaded. Tap 'Load Model' first.") }
            return
        }

        generationJob?.cancel()
        
        val userMsg = ChatMessage(role = Role.USER, text = query)
        val aiMsg = ChatMessage(role = Role.AI, text = "", isGenerating = true)
        
        _uiState.update {
            it.copy(
                query = "",
                isGenerating = true,
                messages = it.messages + userMsg + aiMsg,
                modelError = null
            )
        }

        generationJob = viewModelScope.launch {
            try {
                // 1. Load all embeddings (IO)
                val allEmbeddings = withContext(Dispatchers.IO) { embeddingRepo.getAll() }
                Log.d(TAG, "Loaded ${allEmbeddings.size} embeddings for search")

                // 2. Lazily build pipeline (EmbeddingEngine must be on IO)
                val pipeline = withContext(Dispatchers.IO) {
                    ragPipeline ?: run {
                        val engine = EmbeddingEngine(getApplication())
                        RAGPipeline(engine, llamaEngine).also { ragPipeline = it }
                    }
                }

                // 3. Run RAG
                val result = withContext(Dispatchers.IO) {
                    pipeline.query(query, allEmbeddings)
                }

                // Update AI message with retrieved chunks immediately
                _uiState.update { state ->
                    val updatedMessages = state.messages.map { msg ->
                        if (msg.id == aiMsg.id) msg.copy(retrievedChunks = result.retrievedChunks) else msg
                    }
                    state.copy(messages = updatedMessages)
                }

                // 4. Stream tokens into the answer field
                result.tokenFlow.collect { token ->
                    _uiState.update { state ->
                        val updatedMessages = state.messages.map { msg ->
                            if (msg.id == aiMsg.id) msg.copy(text = msg.text + token) else msg
                        }
                        state.copy(messages = updatedMessages)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "RAG query failed", e)
                _uiState.update {
                    it.copy(modelError = "Generation failed: ${e.message}")
                }
            } finally {
                _uiState.update { state ->
                    val updatedMessages = state.messages.map { msg ->
                        if (msg.id == aiMsg.id) msg.copy(isGenerating = false) else msg
                    }
                    state.copy(isGenerating = false, messages = updatedMessages)
                }
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        _uiState.update { it.copy(isGenerating = false) }
    }

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList()) }
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
    }

    private fun calculateDatabaseSize(): Float {
        val app = getApplication<Application>()
        var totalBytes = 0L
        val dbFiles = listOf(
            "omniview_context",
            "omniview_context-shm",
            "omniview_context-wal"
        )
        dbFiles.forEach { dbName ->
            val file = app.getDatabasePath(dbName)
            if (file.exists()) {
                totalBytes += file.length()
            }
        }
        return totalBytes.toFloat() / (1024f * 1024f)
    }
}
