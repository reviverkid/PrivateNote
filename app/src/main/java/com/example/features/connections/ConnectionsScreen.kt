package com.example.features.connections

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.firebase.firestore.ContactRequest
import com.example.firebase.firestore.FirestoreRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

import com.example.firebase.firestore.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onBack: () -> Unit,
    onOpenChat: (conversationId: String, title: String) -> Unit
) {
    val context = LocalContext.current
    val firestoreRepo = remember { FirestoreRepository() }
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchUserId by remember { mutableStateOf("") }
    var searchResultUser by remember { mutableStateOf<UserProfile?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchErrorMessage by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }

    var allRequests by remember { mutableStateOf<List<ContactRequest>>(emptyList()) }
    var pendingActionUid by remember { mutableStateOf<String?>(null) }

    fun executeSearch() {
        val query = searchUserId.trim()
        if (query.length < 8) {
            searchErrorMessage = "User ID must be at least 8 characters."
            searchResultUser = null
            hasSearched = true
            return
        }
        isSearching = true
        searchErrorMessage = null
        hasSearched = true
        scope.launch {
            val result = firestoreRepo.searchUserByExactId(query)
            isSearching = false
            result.fold(
                onSuccess = { user ->
                    searchResultUser = user
                    if (user == null) {
                        searchErrorMessage = "No user found with exact ID: $query"
                    }
                },
                onFailure = { err ->
                    searchResultUser = null
                    searchErrorMessage = err.localizedMessage ?: "Search error occurred."
                }
            )
        }
    }

    // Stream requests real-time
    LaunchedEffect(Unit) {
        firestoreRepo.getRequestsFlow().collect { requests ->
            allRequests = requests
        }
    }

    val incomingRequests = allRequests.filter { it.toUid == currentUserId && it.status == "pending" }
    val outgoingRequests = allRequests.filter { it.fromUid == currentUserId && it.status == "pending" }
    val acceptedConnections = allRequests.filter { it.status == "accepted" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connections") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Search ID") },
                    icon = { Icon(Icons.Default.PersonSearch, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Requests")
                            if (incomingRequests.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text("${incomingRequests.size}")
                                }
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.Inbox, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Connected")
                            if (acceptedConnections.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                    Text("${acceptedConnections.size}")
                                }
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.People, contentDescription = null) }
                )
            }

            when (selectedTab) {
                0 -> {
                    // PRIVATE USER DISCOVERY TAB
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Private Discovery: Public directories and email/phone lookups are disabled. Enter an exact User ID (min 8 characters) to connect.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = searchUserId,
                            onValueChange = {
                                searchUserId = it
                                searchErrorMessage = null
                            },
                            placeholder = { Text("Enter exact User ID (min 8 chars)...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (isSearching) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(
                                        onClick = { executeSearch() },
                                        enabled = searchUserId.trim().length >= 8,
                                        modifier = Modifier.testTag("submit_search_button")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connection_search_input"),
                            shape = MaterialTheme.shapes.medium,
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (searchErrorMessage != null) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = searchErrorMessage!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        val foundUser = searchResultUser
                        if (foundUser != null) {
                            Text(
                                text = "Search Result",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            val isPendingOutgoing = outgoingRequests.any { it.toUid == foundUser.uid }
                            val isPendingIncoming = incomingRequests.find { it.fromUid == foundUser.uid }
                            val acceptedConn = acceptedConnections.find {
                                (it.fromUid == foundUser.uid && it.toUid == currentUserId) ||
                                        (it.toUid == foundUser.uid && it.fromUid == currentUserId)
                            }
                            val isActionInProgress = pendingActionUid == foundUser.uid

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("connection_card_${foundUser.uid}"),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = MaterialTheme.shapes.large
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                            modifier = Modifier.size(42.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(foundUser.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            Text("@${foundUser.userId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                        }
                                    }

                                    when {
                                        acceptedConn != null -> {
                                            FilledTonalButton(
                                                onClick = {
                                                    acceptedConn.conversationId?.let { convId ->
                                                        onOpenChat(convId, foundUser.displayName)
                                                    }
                                                },
                                                modifier = Modifier.testTag("open_chat_${foundUser.uid}")
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Chat")
                                            }
                                        }
                                        isPendingIncoming != null -> {
                                            Button(
                                                onClick = {
                                                    pendingActionUid = foundUser.uid
                                                    scope.launch {
                                                        val convId = firestoreRepo.acceptConnectionRequest(isPendingIncoming)
                                                        pendingActionUid = null
                                                        if (convId != null) {
                                                            Toast.makeText(context, "Connected with ${foundUser.displayName}!", Toast.LENGTH_SHORT).show()
                                                            onOpenChat(convId, foundUser.displayName)
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.testTag("accept_btn_${foundUser.uid}"),
                                                enabled = !isActionInProgress
                                            ) {
                                                if (isActionInProgress) {
                                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                } else {
                                                    Text("Accept")
                                                }
                                            }
                                        }
                                        isPendingOutgoing -> {
                                            OutlinedButton(
                                                onClick = {},
                                                enabled = false,
                                                modifier = Modifier.testTag("pending_btn_${foundUser.uid}")
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Pending...", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                        else -> {
                                            Button(
                                                onClick = {
                                                    pendingActionUid = foundUser.uid
                                                    scope.launch {
                                                        val success = firestoreRepo.sendConnectionRequest(
                                                            toUid = foundUser.uid,
                                                            toUsername = foundUser.userId,
                                                            toDisplayName = foundUser.displayName
                                                        )
                                                        pendingActionUid = null
                                                        if (success) {
                                                            Toast.makeText(context, "Connection request sent!", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(context, "Failed to send request", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.testTag("connect_btn_${foundUser.uid}"),
                                                enabled = !isActionInProgress
                                            ) {
                                                if (isActionInProgress) {
                                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                } else {
                                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Connect")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (!hasSearched) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.PersonSearch,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Search User by Exact ID",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Enter the 8+ character unique User ID above.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // REQUESTS TAB
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Text(
                                text = "Incoming Requests (${incomingRequests.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (incomingRequests.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("No incoming requests at the moment.", color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        } else {
                            items(incomingRequests, key = { it.id }) { req ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = MaterialTheme.shapes.large
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Surface(
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(req.fromDisplayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                Text("@${req.fromUsername}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    scope.launch {
                                                        firestoreRepo.declineConnectionRequest(req.id)
                                                        Toast.makeText(context, "Request declined", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                modifier = Modifier.testTag("decline_req_${req.id}")
                                            ) {
                                                Text("Decline")
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        val convId = firestoreRepo.acceptConnectionRequest(req)
                                                        if (convId != null) {
                                                            Toast.makeText(context, "Connected with ${req.fromDisplayName}!", Toast.LENGTH_SHORT).show()
                                                            onOpenChat(convId, req.fromDisplayName)
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.testTag("accept_req_${req.id}")
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Accept & Start Chat")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Sent Requests (${outgoingRequests.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        if (outgoingRequests.isEmpty()) {
                            item {
                                Text(
                                    "No outgoing requests pending.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        } else {
                            items(outgoingRequests, key = { it.id }) { req ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(req.toDisplayName, fontWeight = FontWeight.SemiBold)
                                            Text("@${req.toUsername}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                "Pending",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    // CONNECTED TAB
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Connected Contacts (${acceptedConnections.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        if (acceptedConnections.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.PeopleOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("No connections yet. Send or accept requests to connect!", color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(acceptedConnections, key = { it.id }) { conn ->
                                    val isMeSender = conn.fromUid == currentUserId
                                    val partnerName = if (isMeSender) conn.toDisplayName else conn.fromDisplayName
                                    val partnerUsername = if (isMeSender) conn.toUsername else conn.fromUsername

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                        shape = MaterialTheme.shapes.large
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Surface(
                                                    shape = MaterialTheme.shapes.small,
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                    modifier = Modifier.size(44.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(partnerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                    Text("@$partnerUsername", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                                }
                                            }

                                            Button(
                                                onClick = {
                                                    conn.conversationId?.let { convId ->
                                                        onOpenChat(convId, partnerName)
                                                    }
                                                },
                                                modifier = Modifier.testTag("start_chat_${conn.id}")
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Start Chat")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
