package com.example.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.core.notifications.FcmTokenManager
import com.example.core.notifications.NotificationHelper
import com.example.features.authentication.LoginScreen
import com.example.features.authentication.RegisterScreen
import com.example.features.connections.ConnectionsScreen
import com.example.features.conversations.CollectionEntryScreen
import com.example.features.notes.NoteEditorScreen
import com.example.features.notes.NotesHomeScreen
import com.example.features.notes.NotesViewModel
import com.example.features.settings.DiagnosticsScreen
import com.example.features.settings.SettingsScreen
import com.example.features.workspace.PinSetupScreen
import com.example.features.workspace.PrivateLockScreen
import com.example.features.workspace.WorkspaceHomeScreen
import com.example.firebase.auth.AuthRepository
import com.example.firebase.firestore.FirestoreRepository
import com.example.security.PinManager

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val pinManager = remember { PinManager(context) }
    val notesViewModel: NotesViewModel = viewModel()
    val authRepo = remember { AuthRepository() }
    val firestoreRepo = remember { FirestoreRepository() }
    val notificationHelper = remember { NotificationHelper(context) }
    val authUser by authRepo.authStateFlow.collectAsState(initial = authRepo.currentUser)

    // Request POST_NOTIFICATIONS permission on Android 13+ (API 33+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { /* handled */ }

        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Global listener for new conversation messages
    val appOpenTime = remember { System.currentTimeMillis() }
    val lastSeenTimestamps = remember { mutableStateMapOf<String, Long>() }
    var initialConversationsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(authUser?.uid) {
        val currentUid = authUser?.uid
        if (currentUid != null) {
            // Register / sync FCM token for the signed-in user
            FcmTokenManager.syncFcmToken(context, firestoreRepo)

            firestoreRepo.getConversationsFlow().collect { conversations ->
                if (!initialConversationsLoaded) {
                    conversations.forEach { conv ->
                        lastSeenTimestamps[conv.id] = conv.updatedAt
                    }
                    initialConversationsLoaded = true
                } else {
                    conversations.forEach { conv ->
                        val lastSeen = lastSeenTimestamps[conv.id] ?: 0L
                        if (conv.updatedAt > lastSeen) {
                            lastSeenTimestamps[conv.id] = conv.updatedAt
                            if (conv.lastSenderId.isNotBlank() && conv.lastSenderId != currentUid && conv.updatedAt > appOpenTime) {
                                if (!NotificationHelper.isConversationMuted(context, conv.id)) {
                                    notificationHelper.showGenericNotification(
                                        title = "New update",
                                        body = "You have a new update."
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Global listener for incoming connection requests
    val seenRequestIds = remember { mutableSetOf<String>() }
    var initialRequestsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(authUser?.uid) {
        val currentUid = authUser?.uid
        if (currentUid != null) {
            firestoreRepo.getRequestsFlow().collect { requests ->
                if (!initialRequestsLoaded) {
                    requests.forEach { seenRequestIds.add(it.id) }
                    initialRequestsLoaded = true
                } else {
                    requests.forEach { req ->
                        if (req.toUid == currentUid && req.status == "pending" && !seenRequestIds.contains(req.id)) {
                            seenRequestIds.add(req.id)
                            notificationHelper.showGenericNotification(
                                title = "New update",
                                body = "You have a new update."
                            )
                        } else {
                            seenRequestIds.add(req.id)
                        }
                    }
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = "notes_home") {
        composable("notes_home") {
            NotesHomeScreen(
                viewModel = notesViewModel,
                onNoteClick = { noteId ->
                    if (noteId == null) {
                        navController.navigate("note_editor/0")
                    } else {
                        navController.navigate("note_editor/$noteId")
                    }
                },
                onOpenWorkspace = {
                    if (pinManager.hasPin()) {
                        navController.navigate("private_lock")
                    } else {
                        navController.navigate("pin_setup")
                    }
                },
                onOpenSettings = {
                    navController.navigate("notes_settings")
                }
            )
        }

        composable(
            route = "note_editor/{noteId}",
            arguments = listOf(navArgument("noteId") { type = NavType.LongType })
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getLong("noteId")
            val idParam = if (noteId == 0L) null else noteId
            NoteEditorScreen(
                noteId = idParam,
                viewModel = notesViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("notes_settings") {
            SettingsScreen(
                authRepo = authRepo,
                onBack = { navController.popBackStack() },
                onNavigateToPinSetup = { navController.navigate("pin_setup") },
                onNavigateToDiagnostics = { navController.navigate("diagnostics") },
                onNavigateToRegister = { navController.navigate("register") },
                onNavigateToLogin = { navController.navigate("login") },
                onLoggedOut = {
                    navController.navigate("notes_home") {
                        popUpTo("notes_home") { inclusive = true }
                    }
                }
            )
        }

        composable("login") {
            LoginScreen(
                authRepo = authRepo,
                onLoggedIn = {
                    navController.navigate("notes_home") {
                        popUpTo("notes_home") { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate("register") },
                onBack = { navController.popBackStack() }
            )
        }

        composable("register") {
            RegisterScreen(
                authRepo = authRepo,
                firestoreRepo = firestoreRepo,
                onRegistered = {
                    navController.navigate("notes_home") {
                        popUpTo("notes_home") { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("diagnostics") {
            DiagnosticsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("private_lock") {
            PrivateLockScreen(
                pinManager = pinManager,
                onUnlocked = {
                    navController.navigate("workspace_home") {
                        popUpTo("notes_home")
                    }
                },
                onSetupPinRequired = {
                    navController.navigate("pin_setup")
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("pin_setup") {
            PinSetupScreen(
                pinManager = pinManager,
                onPinSet = {
                    navController.navigate("workspace_home") {
                        popUpTo("notes_home") { inclusive = true }
                    }
                }
            )
        }

        composable("workspace_home") {
            WorkspaceHomeScreen(
                onBackToNotes = {
                    navController.navigate("notes_home") {
                        popUpTo("workspace_home") { inclusive = true }
                    }
                },
                onOpenCollection = { id, title ->
                    navController.navigate("collection_entries/$id/$title")
                },
                onOpenConnections = {
                    navController.navigate("connections")
                }
            )
        }

        composable(
            route = "collection_entries/{collectionId}/{collectionTitle}",
            arguments = listOf(
                navArgument("collectionId") { type = NavType.StringType },
                navArgument("collectionTitle") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val collectionId = backStackEntry.arguments?.getString("collectionId") ?: ""
            val collectionTitle = backStackEntry.arguments?.getString("collectionTitle") ?: ""
            CollectionEntryScreen(
                collectionId = collectionId,
                collectionTitle = collectionTitle,
                onBack = { navController.popBackStack() }
            )
        }

        composable("connections") {
            ConnectionsScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { id, title ->
                    navController.navigate("collection_entries/$id/$title")
                }
            )
        }
    }
}
