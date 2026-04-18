package com.omniview.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omniview.app.search.SearchResult

// ── Colour palette (dark AI feel) ─────────────────────────────────────────────
private val AiPurple = Color(0xFFFF5722) // OmniOrange
private val AiPurpleLight = Color(0xFFFFCCBC) // OmniOrangeLight
private val AiSurface = Color(0xFF080606) // Very dark warm background
private val AiCard = Color(0xFF1F1817) // Warm dark grey
private val AiCardBorder = Color(0xFF3D2825)
private val AiInput = Color(0xFF2B201E)
private val GreenReady = Color(0xFF4ADE80)
private val RedError = Color(0xFFF87171)

@Composable
fun AskScreen(
    viewModel: RagViewModel = viewModel(),
    onOpenDrawer: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom as new messages arrive or generation happens
    val lastMessageText = state.messages.lastOrNull()?.text ?: ""
    LaunchedEffect(state.messages.size, lastMessageText) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AiSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // ── Header ──────────────────────────────────────────────────
            AskHeader(onOpenDrawer = onOpenDrawer)

            Spacer(Modifier.height(24.dp))

            // ── Scrollable Chat History ───────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(state.messages) { message ->
                    if (message.role == Role.USER) {
                        UserBubble(message.text)
                    } else {
                        AIBubble(message)
                    }
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

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun AskHeader(onOpenDrawer: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
        }
        Text(
            "OmniView",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .clip(RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 4.dp
                ))
                .background(AiPurple)
                .padding(16.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
private fun AIBubble(message: ChatMessage) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "cursor_alpha"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(AiPurpleLight),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(AiPurple))
        }
        
        Spacer(Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Omni Intelligence",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 20.dp
                    ))
                    .border(1.dp, AiCardBorder, RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 20.dp
                    ))
                    .background(AiCard)
                    .padding(16.dp)
            ) {
                Column {
                    if (message.isGenerating && message.text.isEmpty()) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            color = AiPurple,
                            trackColor = AiInput
                        )
                    } else if (message.text.isNotEmpty()) {
                        val displayText = if (message.isGenerating) "${message.text}▌".replace("▌", "") else message.text
                        Text(
                            text = displayText,
                            color = Color(0xFFE5E7EB),
                            fontSize = 15.sp,
                            lineHeight = 24.sp
                        )
                    }

                    if (message.retrievedChunks.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        message.retrievedChunks.take(1).forEach { chunk ->
                            SourceCardSmall(chunk)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCardSmall(result: SearchResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1311))
            .border(1.dp, Color(0xFF3D2825), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("MEMORY MATCH", color = AiPurple, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                result.app.substringAfterLast(".").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } + " Archive",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "\"${result.text.take(30).replace("\n", " ")}...\"",
                color = Color.Gray,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
                    "Ask your memory...",
                    color = Color(0xFFA1A1AA),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AiInput,
                unfocusedContainerColor = AiInput,
                focusedBorderColor = AiPurple,
                unfocusedBorderColor = Color.Transparent,
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
