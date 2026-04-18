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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.omniview.app.storage.AppStateManager
import com.omniview.app.intelligence.OcrWorkScheduler
import com.omniview.app.intelligence.EmbeddingWorkScheduler
import com.omniview.app.ingestion.ScreenshotService
import com.omniview.app.ui.theme.OmniViewTheme

// ── Navigation destinations ───────────────────────────────────────────────────

private enum class AppTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    ASK("Ask AI", Icons.Default.Search)
}

// ── Activity ─────────────────────────────────────────────────────────────────

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

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        appStateManager = AppStateManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            OmniViewTheme {
                AppNavigation(
                    onStartCapture = { requestScreenCapture() },
                    onPauseResume = { togglePause() },
                    onOpenSettings = { openSettings() },
                    onOpenUsageAccess = { openUsageAccessSettings() },
                    onOpenAccessibility = { openAccessibilitySettings() },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRunOcrNow = { runOcrNow() },
                    onRunEmbeddingNow = { runEmbeddingNow() },
                    appStateManager = appStateManager
                )
            }
        }
    }

    private fun requestScreenCapture() {
        val captureIntent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun togglePause() {
        if (appStateManager.isPaused()) {
            appStateManager.resumeCapture()
        } else {
            appStateManager.pauseCapture()
        }
        recreate()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun runOcrNow() {
        OcrWorkScheduler.scheduleNow(this)
    }

    private fun runEmbeddingNow() {
        EmbeddingWorkScheduler.scheduleNow(this)
    }
}

// ── Root navigation ───────────────────────────────────────────────────────────

@Composable
private fun AppNavigation(
    onStartCapture: () -> Unit,
    onPauseResume: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRunOcrNow: () -> Unit,
    onRunEmbeddingNow: () -> Unit,
    appStateManager: AppStateManager
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                AppTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (selectedTab) {
                AppTab.HOME.ordinal -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                        contentAlignment = Alignment.Center
                    ) {
                        MainScreen(
                            onStartCapture = onStartCapture,
                            onPauseResume = onPauseResume,
                            onOpenSettings = onOpenSettings,
                            onOpenUsageAccess = onOpenUsageAccess,
                            onOpenAccessibility = onOpenAccessibility,
                            onRequestNotifications = onRequestNotifications,
                            onRunOcrNow = onRunOcrNow,
                            onRunEmbeddingNow = onRunEmbeddingNow,
                            appStateManager = appStateManager
                        )
                    }
                }
                AppTab.ASK.ordinal -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    ) {
                        AskScreen()
                    }
                }
            }
        }
    }
}

// ── MainScreen (unchanged from original) ─────────────────────────────────────

@Composable
fun MainScreen(
    onStartCapture: () -> Unit,
    onPauseResume: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRunOcrNow: () -> Unit,
    onRunEmbeddingNow: () -> Unit,
    appStateManager: AppStateManager
) {
    val isPaused = remember { mutableStateOf(appStateManager.isPaused()) }

    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            "OmniView Ingestion & OCR",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(32.dp))

        androidx.compose.material3.Button(onClick = onStartCapture) {
            Text("Start Screenshot Service")
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))

        androidx.compose.material3.Button(onClick = onPauseResume) {
            Text(if (isPaused.value) "Resume Capture" else "Pause Capture")
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))

        androidx.compose.material3.Button(onClick = onOpenSettings) {
            Text("Settings (Blacklist)")
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(32.dp))

        Text("Detection Permissions", style = MaterialTheme.typography.titleMedium)
        Text(
            "Required for app blacklisting to work properly",
            style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))

        androidx.compose.material3.Button(onClick = onOpenUsageAccess) {
            Text("Grant Usage Access")
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))

        androidx.compose.material3.Button(onClick = onOpenAccessibility) {
            Text("Enable Accessibility Service")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.material3.Button(onClick = onRequestNotifications) {
                Text("Grant Notification Permission")
            }
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(48.dp))

        Text("Developer Tools", style = MaterialTheme.typography.labelLarge)

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))

        androidx.compose.material3.Button(
            onClick = onRunOcrNow,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Text("Run OCR Processing Now")
        }

        androidx.compose.material3.Button(
            onClick = onRunEmbeddingNow,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Run Embedding Processing Now")
        }
    }
}