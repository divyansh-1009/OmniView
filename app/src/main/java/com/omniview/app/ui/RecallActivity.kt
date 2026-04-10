package com.omniview.app.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.omniview.app.storage.SearchResult
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecallActivity : ComponentActivity() {

    private val viewModel: RecallViewModel by viewModels()
    private val adapter = SearchResultsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recall)

        val queryInput = findViewById<EditText>(R.id.recallQueryInput)
        val answerText = findViewById<TextView>(R.id.recallAnswerText)
        val statusText = findViewById<TextView>(R.id.recallStatusText)
        val resultsList = findViewById<RecyclerView>(R.id.recallResultsList)

        resultsList.layoutManager = LinearLayoutManager(this)
        resultsList.adapter = adapter

        findViewById<TextView>(R.id.recallSearchButton).setOnClickListener {
            viewModel.search(queryInput.text.toString())
        }
        findViewById<TextView>(R.id.recallAskButton).setOnClickListener {
            answerText.text = ""
            viewModel.ask(queryInput.text.toString())
        }
        queryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.search(queryInput.text.toString())
                true
            } else {
                false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.results.collect { adapter.submit(it) }
                }
                launch {
                    viewModel.answer.collect { answer ->
                        answerText.text = answer.ifBlank { "Streaming answers will appear here." }
                    }
                }
                launch {
                    viewModel.status.collect { status ->
                        statusText.text = status
                    }
                }
            }
        }
    }
}

private class SearchResultsAdapter : RecyclerView.Adapter<SearchResultViewHolder>() {

    private val results = mutableListOf<SearchResult>()

    fun submit(items: List<SearchResult>) {
        results.clear()
        results.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SearchResultViewHolder {
        val density = parent.resources.displayMetrics.density
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }

        val thumbnail = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams((96 * density).toInt(), (72 * density).toInt())
        }
        val textColumn = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(parent.context).apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val snippet = TextView(parent.context).apply {
            textSize = 13f
            maxLines = 4
        }

        textColumn.addView(title)
        textColumn.addView(snippet)
        root.addView(thumbnail)
        root.addView(textColumn)

        return SearchResultViewHolder(root, thumbnail, title, snippet)
    }

    override fun getItemCount(): Int = results.size

    override fun onBindViewHolder(holder: SearchResultViewHolder, position: Int) {
        holder.bind(results[position])
    }
}

private class SearchResultViewHolder(
    itemView: android.view.View,
    private val thumbnail: ImageView,
    private val title: TextView,
    private val snippet: TextView
) : RecyclerView.ViewHolder(itemView) {

    fun bind(result: SearchResult) {
        val file = File(result.screenshotPath)
        title.text = "${result.packageName} - ${FORMATTER.format(Date(result.timestamp))} - d=${"%.3f".format(result.distance)}"
        snippet.text = result.rawText

        if (file.exists()) {
            thumbnail.setImageBitmap(
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = 4 }
                )
            )
        } else {
            thumbnail.setImageResource(android.R.drawable.ic_menu_report_image)
        }
    }

    companion object {
        private val FORMATTER = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    }
}
