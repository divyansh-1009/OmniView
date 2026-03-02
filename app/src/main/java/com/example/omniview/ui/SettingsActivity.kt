package com.example.omniview.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.omniview.ingestion.AppStateManager
import com.example.omniview.ui.theme.OmniViewTheme
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Settings Activity for managing app blacklist and viewing capture status.
 * Supports adding/removing apps from the blacklist (REQ-13, REQ-14).
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var appStateManager: AppStateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        appStateManager = AppStateManager(this)

        setContent {
            OmniViewTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        appStateManager = appStateManager,
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    appStateManager: AppStateManager,
    onBack: () -> Unit
) {
    var blacklist by remember { mutableStateOf(appStateManager.getBlacklist()) }
    var newPackageName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var showAppPicker by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current

    if (showAppPicker) {
        AppPickerList(
            onAppSelected = { appInfo ->
                appStateManager.addToBlacklist(appInfo.packageName)
                blacklist = appStateManager.getBlacklist()
                showAppPicker = false
                errorMessage = ""
            },
            onDismiss = { showAppPicker = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text("Settings - App Blacklist", style = MaterialTheme.typography.headlineSmall)
        }

        // Add app section
        Text("Add New App to Blacklist", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        TextField(
            value = newPackageName,
            onValueChange = { newPackageName = it },
            label = { Text("Package Name") },
            placeholder = { Text("e.g., com.example.app") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val packageName = newPackageName.trim()
                    if (packageName.isEmpty()) {
                        errorMessage = "Package name cannot be empty"
                    } else {
                        try {
                            appStateManager.addToBlacklist(packageName)
                            blacklist = appStateManager.getBlacklist()
                            newPackageName = ""
                            errorMessage = ""
                        } catch (e: Exception) {
                            errorMessage = "Error adding app: ${e.message}"
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Add Package")
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Button(
                onClick = { showAppPicker = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("Select App")
            }
        }

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Blacklist section
        Text("Blacklisted Apps (${blacklist.size})", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        if (blacklist.isEmpty()) {
            Text("No apps blacklisted", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(blacklist.sorted()) { packageName ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            packageName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                appStateManager.removeFromBlacklist(packageName)
                                blacklist = appStateManager.getBlacklist()
                            },
                            modifier = Modifier.widthIn(min = 90.dp)
                        ) {
                            Text("Remove")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clear all button
        if (blacklist.isNotEmpty()) {
            Button(
                onClick = {
                    appStateManager.clearBlacklist()
                    blacklist = appStateManager.getBlacklist()
                    errorMessage = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear All")
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Back button
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }
}

/**
 * Data class to hold app information for the picker.
 */
data class AppInfo(
    val name: String,
    val packageName: String
)

/**
 * Dialog-based App Picker to select an installed app.
 */
@Composable
fun AppPickerList(
    onAppSelected: (AppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps = remember { 
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName == context.packageName }
            .map { 
                AppInfo(
                    name = pm.getApplicationLabel(it).toString(),
                    packageName = it.packageName
                )
            }.sortedBy { it.name.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select App to Blacklist") },
        text = {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                items(apps) { app ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAppSelected(app) }
                            .padding(vertical = 12.dp)
                    ) {
                        Text(app.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            app.packageName, 
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(
                            thickness = 0.5.dp, 
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
