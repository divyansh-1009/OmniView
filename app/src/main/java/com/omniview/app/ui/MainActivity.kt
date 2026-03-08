package com.omniview.app.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.omniview.app.storage.AppStateManager
import com.omniview.app.intelligence.OcrWorkScheduler
import com.omniview.app.intelligence.EmbeddingWorkScheduler
import com.omniview.app.ingestion.ScreenshotService
import com.omniview.app.ui.theme.OmniViewTheme

class MainActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var appStateManager: AppStateManager

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("data", result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        appStateManager = AppStateManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            OmniViewTheme {
                AppRoot(
                    appStateManager = appStateManager,
                    onStartCapture = { requestScreenCapture() },
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onOpenUsageAccess = { 
                        try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } 
                        catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) } 
                    },
                    onOpenAccessibility = { 
                        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } 
                        catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) } 
                    },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRunOcrNow = { OcrWorkScheduler.scheduleNow(this) },
                    onRunEmbeddingNow = { EmbeddingWorkScheduler.scheduleNow(this) },
                    onClearMemories = { clearMemories() }
                )
            }
        }
    }

    private fun requestScreenCapture() {
        val captureIntent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun clearMemories() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = com.omniview.app.storage.ContextDatabase.getInstance(applicationContext)
            db.contextDao().deleteAll()
            db.embeddingDao().deleteAll()
            com.omniview.app.intelligence.OcrQueue.drainAll(applicationContext)
            com.omniview.app.intelligence.EmbeddingQueue.drainAll(applicationContext)
            
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(this@MainActivity, "Memory wiped successfully.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// ── Root navigation ───────────────────────────────────────────────────────────

@Composable
fun AppRoot(
    appStateManager: AppStateManager,
    ragViewModel: RagViewModel = viewModel(),
    onStartCapture: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRunOcrNow: () -> Unit,
    onRunEmbeddingNow: () -> Unit,
    onClearMemories: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF140D0B),
                modifier = Modifier.width(340.dp) // Wider to comfortably fit deep nested items
            ) {
                SettingsDrawerContent(
                    appStateManager = appStateManager,
                    ragViewModel = ragViewModel,
                    onStartCapture = onStartCapture,
                    onOpenSettings = onOpenSettings,
                    onOpenUsageAccess = onOpenUsageAccess,
                    onOpenAccessibility = onOpenAccessibility,
                    onRequestNotifications = onRequestNotifications,
                    onRunOcrNow = onRunOcrNow,
                    onRunEmbeddingNow = onRunEmbeddingNow,
                    onClearMemories = onClearMemories,
                    onCloseDrawer = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AskScreen(
                viewModel = ragViewModel,
                onOpenDrawer = {
                    scope.launch { drawerState.open() }
                }
            )
        }
    }
}

// ── Drawer Content ────────────────────────────────────────────────────────────

@Composable
fun SettingsDrawerContent(
    appStateManager: AppStateManager,
    ragViewModel: RagViewModel,
    onStartCapture: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRunOcrNow: () -> Unit,
    onRunEmbeddingNow: () -> Unit,
    onClearMemories: () -> Unit,
    onCloseDrawer: () -> Unit
) {
    var isRecording by remember { mutableStateOf(!appStateManager.isPaused()) }
    val ragState by ragViewModel.uiState.collectAsState()

    // Auto-sync recording toggle with actual pause state
    LaunchedEffect(isRecording) {
        if (!isRecording && !appStateManager.isPaused()) {
            appStateManager.pauseCapture()
        } else if (isRecording && appStateManager.isPaused()) {
            appStateManager.resumeCapture()
            onStartCapture()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("System Settings", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Text("Neural Link Config", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA1A1AA))
            }
            IconButton(onClick = onCloseDrawer) {
                Icon(Icons.Default.Clear, contentDescription = "Close settings", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        
        // Memory Usage Indicator
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("MEMORY USAGE", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontWeight = FontWeight.Bold)
            Text(String.format("%.1f MB / 100 MB", ragState.databaseSizeMb), style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFCCBC), fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { (ragState.databaseSizeMb / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFFF5722),
            trackColor = Color(0xFF2B201E)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("Embedding database for vector search is synchronized.", fontSize = 11.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(32.dp))

        // Active Model Box
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("ACTIVE MODEL", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1F1817))
                .padding(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Gemma 4", color = Color.White, fontWeight = FontWeight.SemiBold)
                
                if (ragState.modelStatus == ModelStatus.READY) {
                    Button(onClick = { ragViewModel.unloadModel() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), modifier = Modifier.height(30.dp)) {
                        Text("Unload", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                } else if (ragState.modelStatus == ModelStatus.NOT_LOADED || ragState.modelStatus == ModelStatus.ERROR) {
                    Button(onClick = { ragViewModel.loadModel() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), modifier = Modifier.height(30.dp)) {
                        Text("Load", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(onClick = { }, enabled = false, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151)), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), modifier = Modifier.height(30.dp)) {
                        Text("Loading...", fontSize = 11.sp, color = Color.LightGray)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // System Permissions (Re-Added)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SYSTEM PERMISSIONS", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenUsageAccess, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F1817))) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Grant Usage Access", fontSize = 12.sp, color = Color.White)
            }
            Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F1817))) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Enable Accessibility", fontSize = 12.sp, color = Color.White)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F1817))) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grant Notifications", fontSize = 12.sp, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Developer Tools (Re-Added)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("DEVELOPER TOOLS", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { isRecording = !isRecording }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F1817))) {
                Icon(Icons.Default.Build, contentDescription = null, tint = if (isRecording) Color(0xFFF87171) else Color(0xFF4ADE80), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isRecording) "Stop Capture" else "Start Capture", fontSize = 12.sp, color = Color.White)
            }
            Button(
                onClick = { 
                    onRunOcrNow()
                    onRunEmbeddingNow()
                }, 
                modifier = Modifier.fillMaxWidth(), 
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F1817))
            ) {
                Text("Process Data Now", fontSize = 12.sp, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Privacy Shield
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("PRIVACY SHIELD", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontWeight = FontWeight.Bold)
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color(0xFFFFCCBC))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        val blockedApps = appStateManager.getBlacklist()
        if (blockedApps.isEmpty()) {
            Text("No apps blacklisted.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                blockedApps.take(4).forEach { pkg: String ->
                    val displayName = pkg.substringAfterLast(".")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1F1817))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(displayName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }, color = Color.White, fontSize = 14.sp)
                    }
                }
                if (blockedApps.size > 4) {
                    Text("+ ${blockedApps.size - 4} more", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Clear Memory
        Button(
            onClick = onClearMemories,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B1A1A)),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("CLEAR MEMORY", color = Color(0xFFF87171), fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text("About OmniView", color = Color.Gray, fontSize = 12.sp)
        }
    }
}
