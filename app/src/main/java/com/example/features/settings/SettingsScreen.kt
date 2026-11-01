package com.example.features.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.notifications.NotificationHelper
import com.example.firebase.auth.AuthRepository
import com.example.firebase.firestore.FirestoreRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authRepo: AuthRepository,
    onBack: () -> Unit,
    onNavigateToPinSetup: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val firestoreRepo = remember { FirestoreRepository() }
    val scope = rememberCoroutineScope()

    var notificationsEnabled by remember { mutableStateOf(true) }
    var readReceiptsEnabled by remember { mutableStateOf(NotificationHelper.areReadReceiptsEnabled(context)) }
    val currentUser = authRepo.currentUser

    var myUserId by remember { mutableStateOf("") }
    var myDisplayName by remember { mutableStateOf("") }
    var showEditIdDialog by remember { mutableStateOf(false) }
    var newUserIdInput by remember { mutableStateOf("") }
    var editIdError by remember { mutableStateOf<String?>(null) }
    var isSavingId by remember { mutableStateOf(false) }

    LaunchedEffect(currentUser?.uid) {
        val profile = firestoreRepo.getCurrentUserProfile()
        myUserId = profile?.get("userId") ?: profile?.get("username") ?: (currentUser?.uid ?: "")
        myDisplayName = profile?.get("displayName") ?: "User"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Account") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Authentication & Session", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column {
                        if (currentUser != null) {
                            ListItem(
                                headlineContent = { Text("Signed in as") },
                                supportingContent = { Text(currentUser.email ?: "Authenticated User") },
                                leadingContent = { Icon(Icons.Default.Person, contentDescription = null) }
                            )
                            HorizontalDivider()
                            ListItem(
                                headlineContent = { Text("Unique User ID") },
                                supportingContent = {
                                    Text(
                                        text = if (myUserId.isNotBlank()) "@$myUserId" else "Loading ID...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                leadingContent = { Icon(Icons.Default.Badge, contentDescription = null) },
                                trailingContent = {
                                    Row {
                                        IconButton(
                                            onClick = {
                                                if (myUserId.isNotBlank()) {
                                                    clipboardManager.setText(AnnotatedString(myUserId))
                                                    Toast.makeText(context, "User ID copied to clipboard", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.testTag("copy_user_id_button")
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy User ID")
                                        }
                                        IconButton(
                                            onClick = {
                                                newUserIdInput = myUserId
                                                editIdError = null
                                                showEditIdDialog = true
                                            },
                                            modifier = Modifier.testTag("edit_user_id_button")
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit User ID")
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                            ListItem(
                                headlineContent = { Text("Sign Out / Logout") },
                                supportingContent = { Text("End current secure session") },
                                leadingContent = { Icon(Icons.Default.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                modifier = Modifier
                                    .clickable(onClick = {
                                        authRepo.signOut()
                                        onLoggedOut()
                                    })
                                    .testTag("logout_item")
                            )
                        } else {
                            ListItem(
                                headlineContent = { Text("Sign In") },
                                supportingContent = { Text("Access your cloud account") },
                                leadingContent = { Icon(Icons.Default.Login, contentDescription = null) },
                                modifier = Modifier
                                    .clickable(onClick = onNavigateToLogin)
                                    .testTag("login_item")
                            )
                            HorizontalDivider()
                            ListItem(
                                headlineContent = { Text("Register New Account") },
                                supportingContent = { Text("Create a new Firebase cloud account") },
                                leadingContent = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                                modifier = Modifier
                                    .clickable(onClick = onNavigateToRegister)
                                    .testTag("register_account_item")
                            )
                        }
                    }
                }
            }

            item {
                Text("Security & Access", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    ListItem(
                        headlineContent = { Text("Change Secret PIN") },
                        supportingContent = { Text("Update workspace unlock PIN") },
                        leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                        modifier = Modifier
                            .clickable(onClick = onNavigateToPinSetup)
                            .testTag("change_pin_item")
                    )
                }
            }

            item {
                Text("Backend & Diagnostics", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    ListItem(
                        headlineContent = { Text("Firebase & Diagnostics") },
                        supportingContent = { Text("View quotas, cache size & mode toggle") },
                        leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
                        modifier = Modifier
                            .clickable(onClick = onNavigateToDiagnostics)
                            .testTag("diagnostics_item")
                    )
                }
            }

            item {
                Text("Preferences", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column {
                        ListItem(
                            headlineContent = { Text("Notifications") },
                            supportingContent = { Text("Generic alert notifications") },
                            leadingContent = { Icon(Icons.Default.Notifications, contentDescription = null) },
                            trailingContent = {
                                Switch(
                                    checked = notificationsEnabled,
                                    onCheckedChange = {
                                        notificationsEnabled = it
                                        scope.launch {
                                            firestoreRepo.setGlobalNotificationsEnabled(it)
                                        }
                                    },
                                    modifier = Modifier.testTag("notifications_switch")
                                )
                            }
                        )
                        HorizontalDivider()
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val notifHelper = remember { com.example.core.notifications.NotificationHelper(context) }
                        ListItem(
                            headlineContent = { Text("Test Notification") },
                            supportingContent = { Text("Send immediate test alert") },
                            leadingContent = { Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier
                                .clickable {
                                    notifHelper.showGenericNotification(
                                        title = "Private Notes Alert",
                                        body = "New secure update received."
                                    )
                                    android.widget.Toast.makeText(context, "Notification sent! Check your notification bar.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .testTag("test_notification_item")
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("Read Receipts") },
                            supportingContent = { Text("Share view status in collections") },
                            leadingContent = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                            trailingContent = {
                                Switch(
                                    checked = readReceiptsEnabled,
                                    onCheckedChange = {
                                        readReceiptsEnabled = it
                                        NotificationHelper.setReadReceiptsEnabled(context, it)
                                    },
                                    modifier = Modifier.testTag("read_receipts_switch")
                                )
                            }
                        )
                    }
                }
            }

            item {
                Text("About", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    ListItem(
                        headlineContent = { Text("Private Notes Workspace") },
                        supportingContent = { Text("Version 1.0.0 • Firebase Spark Connected") },
                        leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
                    )
                }
            }
        }
    }

    if (showEditIdDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isSavingId) showEditIdDialog = false
            },
            icon = { Icon(Icons.Default.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Edit Unique User ID") },
            text = {
                Column {
                    Text(
                        text = "Your User ID is how other users discover and connect with you. Must be at least 8 characters (letters, numbers, underscores, and dashes).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newUserIdInput,
                        onValueChange = {
                            newUserIdInput = it
                            editIdError = null
                        },
                        label = { Text("User ID (min 8 chars)") },
                        leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_user_id_input"),
                        isError = editIdError != null
                    )
                    if (editIdError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = editIdError!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newUserIdInput.trim()
                        if (trimmed.length < 8) {
                            editIdError = "User ID must be at least 8 characters."
                            return@Button
                        }
                        isSavingId = true
                        editIdError = null
                        scope.launch {
                            val result = firestoreRepo.updateCustomUserId(trimmed)
                            isSavingId = false
                            result.fold(
                                onSuccess = {
                                    myUserId = trimmed
                                    showEditIdDialog = false
                                    Toast.makeText(context, "User ID updated successfully!", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = { err ->
                                    editIdError = err.localizedMessage ?: "Failed to update User ID"
                                }
                            )
                        }
                    },
                    enabled = !isSavingId && newUserIdInput.trim().length >= 8,
                    modifier = Modifier.testTag("save_user_id_button")
                ) {
                    if (isSavingId) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditIdDialog = false },
                    enabled = !isSavingId
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
