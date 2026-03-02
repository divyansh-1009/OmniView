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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.omniview.ingestion.AppStateManager
import com.example.omniview.ui.theme.OmniViewTheme

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
    val blacklist = remember { mutableStateOf(appStateManager.getBlacklist()) }
    val newPackageName = remember { mutableStateOf("") }
    val errorMessage = remember { mutableStateOf("") }

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
            value = newPackageName.value,
            onValueChange = { newPackageName.value = it },
            label = { Text("Package Name") },
            placeholder = { Text("e.g., com.example.app") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val packageName = newPackageName.value.trim()
                if (packageName.isEmpty()) {
                    errorMessage.value = "Package name cannot be empty"
                } else {
                    try {
                        appStateManager.addToBlacklist(packageName)
                        blacklist.value = appStateManager.getBlacklist()
                        newPackageName.value = ""
                        errorMessage.value = ""
                    } catch (e: Exception) {
                        errorMessage.value = "Error adding app: ${e.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add to Blacklist")
        }

        if (errorMessage.value.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                errorMessage.value,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Blacklist section
        Text("Blacklisted Apps (${blacklist.value.size})", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        if (blacklist.value.isEmpty()) {
            Text("No apps blacklisted", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(blacklist.value.sorted()) { packageName ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            packageName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )

                        Button(
                            onClick = {
                                appStateManager.removeFromBlacklist(packageName)
                                blacklist.value = appStateManager.getBlacklist()
                            },
                            modifier = Modifier.width(60.dp)
                        ) {
                            Text("Remove")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clear all button
        if (blacklist.value.isNotEmpty()) {
            Button(
                onClick = {
                    appStateManager.clearBlacklist()
                    blacklist.value = appStateManager.getBlacklist()
                    errorMessage.value = ""
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
