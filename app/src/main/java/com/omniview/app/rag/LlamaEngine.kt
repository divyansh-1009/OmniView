package com.omniview.app.rag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaHelper
import org.nehuatl.llamacpp.LlamaHelper.LLMEvent
import java.io.File

/**
 * Singleton wrapper around [LlamaHelper].
 *
 * Loads a GGUF model from the app's internal files directory and exposes
 * a streaming [generate] function that emits tokens as they are produced.
 *
 * All heavy calls happen on [Dispatchers.IO].
 */
class LlamaEngine private constructor() {

    companion object {
        private const val TAG = "OmniView:LlamaEngine"
        const val MODEL_FILENAME = "gemma-3-1b-it-q4_k_m.gguf"
        private const val CONTEXT_LENGTH = 4096

        @Volatile
        private var INSTANCE: LlamaEngine? = null

        fun getInstance(): LlamaEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlamaEngine().also { INSTANCE = it }
            }
    }

    private var llamaHelper: LlamaHelper? = null
    private val llmEvents = MutableSharedFlow<LLMEvent>(extraBufferCapacity = 64)
    private val scope = CoroutineScope(Dispatchers.IO)

    enum class State { NOT_LOADED, LOADING, READY, ERROR }

    private val _stateFlow = MutableStateFlow(State.NOT_LOADED)
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    val state: State get() = _stateFlow.value

    @Volatile
    var errorMessage: String? = null
        private set

    fun isReady() = state == State.READY

    /**
     * Returns the model file from the app's internal files directory.
     * The user pushes the GGUF file to:
     *   /data/data/com.omniview.app.ui/files/gemma-3-1b-it-q4_k_m.gguf
     */
    fun getModelFile(context: Context): File =
        File(context.filesDir, MODEL_FILENAME)

    /**
     * Loads the GGUF model asynchronously. Calls [onComplete] with `true`
     * on success or `false` on failure.
     */
    suspend fun load(context: Context, onComplete: (success: Boolean) -> Unit) {
        if (state == State.READY) {
            onComplete(true)
            return
        }
        _stateFlow.value = State.LOADING
        
        // Initialize helper if not done yet
        if (llamaHelper == null) {
            llamaHelper = LlamaHelper(
                context.contentResolver,
                scope,
                llmEvents
            )
        }

        withContext(Dispatchers.IO) {
            try {
                val modelFile = getModelFile(context)
                if (!modelFile.exists()) {
                    throw IllegalStateException(
                        "Model file not found: ${modelFile.absolutePath}\n" +
                        "Push it with: adb push gemma-3-1b-it-q4_k_m.gguf " +
                        "/data/data/com.omniview.app.ui/files/"
                    )
                }
                Log.i(TAG, "Loading model from ${modelFile.absolutePath} (${modelFile.length() / (1024 * 1024)} MB)")

                // The library natively passes this string directly into Uri.parse(), so it MUST have the scheme.
                val uriString = "file://${modelFile.absolutePath}"
                Log.d(TAG, "Instructing LlamaHelper to load from: $uriString")

                llamaHelper?.load(
                    uriString,
                    CONTEXT_LENGTH,
                    null
                ) { _ -> 
                    Log.i(TAG, "Model loaded successfully")
                    errorMessage = null
                    _stateFlow.value = State.READY
                    onComplete(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                errorMessage = e.message ?: "Unknown error loading model"
                _stateFlow.value = State.ERROR
                onComplete(false)
            }
        }
    }

    /**
     * Streams generated tokens for the given [prompt].
     * Emits token strings one at a time as the model produces them.
     */
    fun generate(prompt: String): Flow<String> = channelFlow {
        check(state == State.READY && llamaHelper != null) { "Model is not loaded" }
        Log.d(TAG, "Starting generation for prompt (${prompt.length} chars)")
        
        val job = launch {
            llmEvents.collect { event ->
                when (event) {
                    is LLMEvent.Ongoing -> {
                        Log.d(TAG, "Ongoing token received: ${event.word}")
                        send(event.word)
                    }
                    is LLMEvent.Done -> {
                        Log.d(TAG, "Done event received")
                        close()
                    }
                    is LLMEvent.Error -> {
                        Log.e(TAG, "Generation error")
                        close(IllegalStateException("LLM generation failed"))
                    }
                    else -> {} // ignore Loaded, Started etc
                }
            }
        }
        Log.d(TAG, "Calling predict natively")
        llamaHelper?.predict(prompt, null, true)
        Log.d(TAG, "Predict call returned, waiting for job")
        
        // Wait for flow collection to end
        job.join()
        Log.d(TAG, "Job joined, generation finished")
    }.flowOn(Dispatchers.IO)

    fun release() {
        try {
            llamaHelper?.release()
            _stateFlow.value = State.NOT_LOADED
            Log.i(TAG, "Model released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing model", e)
        }
    }
}
