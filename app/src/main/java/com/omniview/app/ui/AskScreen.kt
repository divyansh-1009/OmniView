package com.omniview.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omniview.app.search.SearchResult

// ── Colour palette (dark AI feel) ─────────────────────────────────────────────
private val AiPurple = Color(0xFF8B5CF6)
private val AiPurpleLight = Color(0xFFC4B5FD)
private val AiSurface = Color(0xFF1A1625)
private val AiCard = Color(0xFF231D35)
private val AiCardBorder = Color(0xFF3D3356)
private val AiInput = Color(0xFF2A2240)
private val GreenReady = Color(0xFF4ADE80)
private val YellowLoading = Color(0xFFFBBF24)
private val RedError = Color(0xFFF87171)

@Composable
fun AskScreen(viewModel: RagViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom as tokens arrive
    LaunchedEffect(state.answer.length) {
        if (state.answer.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AiSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // ── Header ──────────────────────────────────────────────────
            AskHeader()

            Spacer(Modifier.height(16.dp))

            // ── Model Status Bar ─────────────────────────────────────────
            ModelStatusBar(
                status = state.modelStatus,
                errorMessage = state.modelError,
                embeddingCount = state.embeddingCount,
                onLoadModel = { viewModel.loadModel() },
                onUnloadModel = { viewModel.unloadModel() }
            )

            Spacer(Modifier.height(16.dp))

            // ── Scrollable content ──────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                reverseLayout = false
            ) {
                // Answer panel
                if (state.isGenerating || state.answer.isNotEmpty()) {
                    item {
                        AnswerPanel(
                            answer = state.answer,
                            isGenerating = state.isGenerating
                        )
                    }
                }

                // Source cards header
                if (state.retrievedChunks.isNotEmpty()) {
                    item {
                        SourcesHeader(
                            count = state.retrievedChunks.size,
                            expanded = state.showSources,
                            onToggle = { viewModel.toggleSources() }
                        )
                    }
                }

                // Source chunk cards
                if (state.showSources && state.retrievedChunks.isNotEmpty()) {
                    items(state.retrievedChunks) { chunk ->
                        SourceCard(chunk)
                    }
                }

                // Empty state
                if (!state.isGenerating && state.answer.isEmpty() && state.modelStatus == ModelStatus.READY) {
                    item { EmptyState() }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Query Input ──────────────────────────────────────────────
            QueryInputBar(
                query = state.query,
                isGenerating = state.isGenerating,
                enabled = state.modelStatus == ModelStatus.READY,
                onQueryChange = viewModel::setQuery,
                onSubmit = viewModel::submitQuery,
                onStop = viewModel::cancelGeneration
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun AskHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(AiPurple, Color(0xFF6D28D9)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("G", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                "Ask OmniView",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                "Powered by Gemma 3 · llama.cpp",
                style = MaterialTheme.typography.labelSmall,
                color = AiPurpleLight
            )
        }
    }
}

@Composable
private fun ModelStatusBar(
    status: ModelStatus,
    errorMessage: String?,
    embeddingCount: Int,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AiCard)
            .border(1.dp, AiCardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status dot
        val dotColor by animateColorAsState(
            targetValue = when (status) {
                ModelStatus.READY -> GreenReady
                ModelStatus.LOADING -> YellowLoading
                ModelStatus.ERROR -> RedError
                ModelStatus.NOT_LOADED -> Color.Gray
            },
            label = "dot_color"
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (status) {
                    ModelStatus.NOT_LOADED -> "Model not loaded"
                    ModelStatus.LOADING -> "Loading model…"
                    ModelStatus.READY -> "Gemma 3 1B · Ready"
                    ModelStatus.ERROR -> "Load failed"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            if (status == ModelStatus.READY) {
                Text(
                    "$embeddingCount context chunks available",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9CA3AF)
                )
            }
            if (status == ModelStatus.ERROR && errorMessage != null) {
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = RedError,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (status == ModelStatus.LOADING) {
            var targetProgress by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(status) {
                targetProgress = 0.95f // slowly approach 95% over 15 seconds
            }
            val progress by animateFloatAsState(
                targetValue = targetProgress,
                animationSpec = tween(durationMillis = 15000, easing = LinearEasing),
                label = "loading_progress"
            )
            val percent = (progress * 100).toInt()
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = YellowLoading,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    progress = progress,
                    modifier = Modifier.size(22.dp),
                    color = YellowLoading,
                    strokeWidth = 2.dp
                )
            }
        } else if (status == ModelStatus.READY) {
            FilledTonalButton(
                onClick = onUnloadModel,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFF374151),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Unload", style = MaterialTheme.typography.labelSmall)
            }
        } else {
            FilledTonalButton(
                onClick = onLoadModel,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = AiPurple,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Load Model", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AnswerPanel(answer: String, isGenerating: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "cursor_alpha"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AiCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, AiCardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isGenerating) AiPurple else GreenReady)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isGenerating) "Generating…" else "Answer",
                    style = MaterialTheme.typography.labelMedium,
                    color = AiPurpleLight,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (isGenerating && answer.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    color = AiPurple,
                    trackColor = AiInput
                )
            } else if (answer.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                val displayText = if (isGenerating) "$answer▌".replace("▌", "") else answer
                Row {
                    Text(
                        text = answer,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Default,
                            lineHeight = 24.sp
                        ),
                        color = Color(0xFFE5E7EB)
                    )
                    // Blinking cursor while generating
                    if (isGenerating) {
                        Text(
                            text = "▌",
                            color = AiPurple,
                            modifier = Modifier.alpha(cursorAlpha),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle() }
            .background(AiInput)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "📚 Sources · $count chunks retrieved",
            style = MaterialTheme.typography.labelMedium,
            color = AiPurpleLight,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = AiPurpleLight,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SourceCard(result: SearchResult) {
    val timeStr = android.text.format.DateFormat.format("MMM d, h:mm a", result.timestamp)
    val scorePercent = (result.score * 100).toInt()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AiCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, AiCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    result.app.substringAfterLast(".").uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = AiPurple,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AiPurple.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "$scorePercent% match",
                            style = MaterialTheme.typography.labelSmall,
                            color = AiPurpleLight
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                result.text.take(200).trim(),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFA1A1AA),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "$timeStr",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6B7280)
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🔍", fontSize = 42.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Ask anything about your screen history",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF6B7280),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "e.g. \"What was I reading in Chrome yesterday?\"",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF4B5563),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun QueryInputBar(
    query: String,
    isGenerating: Boolean,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    "Ask about your screen history…",
                    color = Color(0xFF6B7280),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AiInput,
                unfocusedContainerColor = AiInput,
                focusedBorderColor = AiPurple,
                unfocusedBorderColor = AiCardBorder,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = AiPurple
            ),
            maxLines = 4,
            enabled = !isGenerating,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (enabled) onSubmit() })
        )

        // Send / Stop button
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    if (isGenerating) RedError.copy(alpha = 0.85f)
                    else if (enabled && query.isNotBlank()) AiPurple
                    else Color(0xFF374151)
                )
                .clickable(enabled = isGenerating || (enabled && query.isNotBlank())) {
                    if (isGenerating) onStop() else onSubmit()
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(targetState = isGenerating, label = "btn_icon") { generating ->
                Icon(
                    imageVector = if (generating) Icons.Default.Close else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (generating) "Stop" else "Send",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
