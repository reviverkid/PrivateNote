package com.example.features.conversations

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.notifications.NotificationHelper
import com.example.firebase.firestore.FirestoreRepository
import com.example.firebase.firestore.RemoteMessage
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CollectionEntryScreen(
    collectionId: String,
    collectionTitle: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val firestoreRepo = remember { FirestoreRepository() }
    val currentAuthUser = FirebaseAuth.getInstance().currentUser
    val currentUserId = currentAuthUser?.uid ?: "unknown"
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<RemoteMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var typingJob by remember { mutableStateOf<Job?>(null) }

    var myProfile by remember { mutableStateOf<Map<String, String>?>(firestoreRepo.cachedMyProfile) }

    fun isSelf(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val cleanName = name.removePrefix("Record: ").trim()
        val myDisplay = myProfile?.get("displayName") ?: currentAuthUser?.displayName
        val myUser = myProfile?.get("username")
        val myEmailName = currentAuthUser?.email?.substringBefore("@")
        val myEmail = currentAuthUser?.email

        return (!myDisplay.isNullOrBlank() && cleanName.equals(myDisplay, ignoreCase = true)) ||
                (!myUser.isNullOrBlank() && cleanName.equals(myUser, ignoreCase = true)) ||
                (!myEmailName.isNullOrBlank() && cleanName.equals(myEmailName, ignoreCase = true)) ||
                (!myEmail.isNullOrBlank() && cleanName.equals(myEmail, ignoreCase = true)) ||
                (!myDisplay.isNullOrBlank() && cleanName.contains(myDisplay, ignoreCase = true))
    }

    var partnerDisplayName by remember {
        val clean = collectionTitle.removePrefix("Record: ").trim()
        mutableStateOf(if (clean.isBlank() || isSelf(clean)) "" else clean)
    }
    var partnerUid by remember { mutableStateOf<String?>(null) }
    var isPartnerTyping by remember { mutableStateOf(false) }
    var isPartnerOnline by remember { mutableStateOf(false) }

    // Settings & Privacy State
    var disappearingPolicy by remember { mutableStateOf("never") }
    var unreadOnlyModeEnabled by remember { mutableStateOf(NotificationHelper.isUnreadOnlyMode(context, collectionId)) }
    var isFullHistoryRevealed by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(NotificationHelper.isConversationMuted(context, collectionId)) }

    // Dialog & Menu visibility
    var showMenu by remember { mutableStateOf(false) }
    var showDisappearingDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var selectedMessageForAction by remember { mutableStateOf<RemoteMessage?>(null) }

    val senderNamesMap = remember { mutableStateMapOf<String, String>() }
    val notificationHelper = remember { NotificationHelper(context) }
    val screenOpenTime = remember { System.currentTimeMillis() }
    val seenMessageIds = remember { mutableSetOf<String>() }

    // Session unread tracking & delivery tracking
    val alreadyReadOnOpen = remember { mutableSetOf<String>() }
    val alreadyMarkedDeliveredIds = remember { mutableSetOf<String>() }
    val areReadReceiptsEnabled = remember { NotificationHelper.areReadReceiptsEnabled(context) }
    var hasInitializedReadTracking by remember { mutableStateOf(false) }

    // Fetch user profile immediately
    LaunchedEffect(currentUserId) {
        val profile = firestoreRepo.getCurrentUserProfile()
        myProfile = profile
        if (isSelf(partnerDisplayName)) {
            partnerDisplayName = ""
        }
    }

    // Resolve conversation partner's real name and UID from conversation details
    LaunchedEffect(collectionId) {
        val conv = firestoreRepo.getConversationDetails(collectionId)
        if (conv != null) {
            disappearingPolicy = conv.disappearingPolicy
            val otherUid = conv.participantIds.firstOrNull { it != currentUserId }
            if (otherUid != null) {
                partnerUid = otherUid
                val resolvedOther = firestoreRepo.resolveUserName(otherUid)
                if (resolvedOther.isNotBlank() && resolvedOther != "User" && !isSelf(resolvedOther)) {
                    partnerDisplayName = resolvedOther
                    senderNamesMap[otherUid] = resolvedOther
                }
            }
        }
    }

    // Listen to real-time conversation snapshot for typing status & policy updates
    LaunchedEffect(collectionId, partnerUid) {
        firestoreRepo.getConversationSnapshotFlow(collectionId).collect { data ->
            if (data != null) {
                data["disappearingPolicy"]?.toString()?.let { disappearingPolicy = it }
                if (partnerUid != null) {
                    val typingMap = data["typing"] as? Map<*, *>
                    val timestamp = (typingMap?.get(partnerUid) as? Number)?.toLong() ?: 0L
                    isPartnerTyping = (System.currentTimeMillis() - timestamp) < 4000L
                } else {
                    isPartnerTyping = false
                }
            } else {
                isPartnerTyping = false
            }
        }
    }

    // Periodically re-evaluate typing expiration if true
    LaunchedEffect(isPartnerTyping) {
        if (isPartnerTyping) {
            delay(4000L)
            isPartnerTyping = false
        }
    }

    // Listen to partner presence
    LaunchedEffect(partnerUid) {
        val uid = partnerUid
        if (!uid.isNullOrBlank()) {
            firestoreRepo.getUserPresenceFlow(uid).collect { (online, lastActive) ->
                isPartnerOnline = online || (System.currentTimeMillis() - lastActive < 120000L)
            }
        }
    }

    // Update current user's presence & clear typing on leave
    LaunchedEffect(currentUserId) {
        firestoreRepo.updateUserPresence(true)
        while (true) {
            delay(30000L)
            firestoreRepo.updateUserPresence(true)
        }
    }

    // Cleanup & commit read statuses when leaving conversation
    DisposableEffect(collectionId) {
        onDispose {
            // Restore unread-only mode automatically on exit
            isFullHistoryRevealed = false
            scope.launch {
                try {
                    firestoreRepo.setTypingStatus(collectionId, false)
                    // Mark messages viewed during this session as read in Firestore only if read receipts are enabled
                    if (areReadReceiptsEnabled) {
                        val unreadIds = messages.filter {
                            it.senderId != currentUserId && !it.readBy.contains(currentUserId)
                        }.map { it.id }
                        if (unreadIds.isNotEmpty()) {
                            firestoreRepo.markMessagesAsViewed(collectionId, unreadIds)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // Helper: is message expired according to disappearing policy
    fun isMessageExpired(msg: RemoteMessage): Boolean {
        if (disappearingPolicy == "never" || disappearingPolicy.isBlank()) return false
        val viewTime = msg.viewedAt[currentUserId] ?: return false
        val now = System.currentTimeMillis()
        return when (disappearingPolicy) {
            "once_viewed" -> alreadyReadOnOpen.contains(msg.id)
            "1_hour" -> (now - viewTime) >= 3600_000L
            "24_hours" -> (now - viewTime) >= 86400_000L
            "7_days" -> (now - viewTime) >= 7 * 86400_000L
            else -> false
        }
    }

    LaunchedEffect(collectionId) {
        firestoreRepo.getMessagesFlow(collectionId).collect { list ->
            if (!hasInitializedReadTracking) {
                list.forEach { msg ->
                    if (msg.readBy.contains(currentUserId) || msg.viewedAt.containsKey(currentUserId)) {
                        alreadyReadOnOpen.add(msg.id)
                    }
                }
                hasInitializedReadTracking = true
            }

            // Mark delivered status for incoming messages synchronized on this device
            val undeliveredIncoming = list.filter {
                it.senderId != currentUserId && it.senderId.isNotBlank() &&
                        !it.deliveredTo.contains(currentUserId) &&
                        !alreadyMarkedDeliveredIds.contains(it.id)
            }.map { it.id }

            if (undeliveredIncoming.isNotEmpty()) {
                alreadyMarkedDeliveredIds.addAll(undeliveredIncoming)
                scope.launch {
                    firestoreRepo.markMessagesAsDelivered(collectionId, undeliveredIncoming)
                }
            }

            list.forEach { msg ->
                if (msg.senderId != currentUserId && msg.senderId.isNotBlank()) {
                    if (partnerUid == null) {
                        partnerUid = msg.senderId
                    }
                    if (msg.senderName.isNotBlank() && msg.senderName != "User" && !isSelf(msg.senderName)) {
                        senderNamesMap[msg.senderId] = msg.senderName
                        if (partnerDisplayName.isBlank() || isSelf(partnerDisplayName)) {
                            partnerDisplayName = msg.senderName
                        }
                    } else if (!senderNamesMap.containsKey(msg.senderId) || isSelf(senderNamesMap[msg.senderId])) {
                        scope.launch {
                            val resolved = firestoreRepo.resolveUserName(msg.senderId)
                            if (resolved.isNotBlank() && resolved != "User" && !isSelf(resolved)) {
                                senderNamesMap[msg.senderId] = resolved
                                if (partnerDisplayName.isBlank() || isSelf(partnerDisplayName)) {
                                    partnerDisplayName = resolved
                                }
                            }
                        }
                    }

                    if (msg.createdAt > screenOpenTime && !seenMessageIds.contains(msg.id)) {
                        seenMessageIds.add(msg.id)
                        if (!NotificationHelper.isConversationMuted(context, collectionId)) {
                            notificationHelper.showGenericNotification(
                                title = "New update",
                                body = "You have a new update."
                            )
                        }
                    } else {
                        seenMessageIds.add(msg.id)
                    }
                }
            }
            messages = list

            // Background garbage collection of expired messages
            val expired = list.filter { isMessageExpired(it) }
            if (expired.isNotEmpty()) {
                scope.launch {
                    expired.forEach { firestoreRepo.deleteMessage(collectionId, it.id, true) }
                }
            }
        }
    }

    // Filter messages: exclude deleted, deleted for this user, and expired
    val validMessages = messages.filter { msg ->
        !msg.isDeleted && !msg.deletedFor.contains(currentUserId) && !isMessageExpired(msg)
    }

    // Unread-Only Mode filter:
    // When enabled and history is NOT temporarily revealed, show unread messages only,
    // PLUS the latest message from the sender and the latest message from recipient (so user knows if reply was sent).
    val visibleMessages = if (unreadOnlyModeEnabled && !isFullHistoryRevealed) {
        val unreadIncoming = validMessages.filter { msg ->
            msg.senderId != currentUserId && !alreadyReadOnOpen.contains(msg.id)
        }
        val latestFromMe = validMessages.filter { it.senderId == currentUserId }.maxByOrNull { it.createdAt }
        val latestFromPartner = validMessages.filter { it.senderId != currentUserId }.maxByOrNull { it.createdAt }
        val anchorIds = setOfNotNull(latestFromMe?.id, latestFromPartner?.id)

        validMessages.filter { msg ->
            unreadIncoming.any { it.id == msg.id } || anchorIds.contains(msg.id)
        }
    } else {
        validMessages
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    val headerName = when {
                        partnerDisplayName.isNotBlank() && !isSelf(partnerDisplayName) -> partnerDisplayName
                        senderNamesMap.values.any { !isSelf(it) && it != "User" } -> senderNamesMap.values.first { !isSelf(it) && it != "User" }
                        else -> "Peer"
                    }
                    val initial = headerName.firstOrNull()?.uppercaseChar()?.toString() ?: "P"

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = initial,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            if (isPartnerOnline) {
                                Box(
                                    modifier = Modifier
                                        .size(11.dp)
                                        .align(Alignment.BottomEnd)
                                        .background(Color(0xFF4CAF50), CircleShape)
                                        .padding(1.5.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = headerName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isMuted) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.NotificationsOff,
                                        contentDescription = "Muted",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(1.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isPartnerTyping) {
                                    Text(
                                        text = "typing...",
                                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF2E7D32)
                                    )
                                } else if (isPartnerOnline) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(Color(0xFF4CAF50), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "online",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Medium
                                    )
                                } else {
                                    Text(
                                        text = if (disappearingPolicy != "never") "disappearing active" else "active recently",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back_to_workspace_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Message Visibility Toggle Button (Eye Icon)
                    IconButton(
                        onClick = {
                            isFullHistoryRevealed = !isFullHistoryRevealed
                            val toastMsg = if (isFullHistoryRevealed) "Full history revealed" else "Restored unread-only view"
                            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("visibility_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isFullHistoryRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (isFullHistoryRevealed) "Hide history" else "Reveal history",
                            tint = if (isFullHistoryRevealed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Three-dot Menu Icon
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.testTag("conversation_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Chat Options",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Disappearing Messages") },
                                leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showDisappearingDialog = true
                                },
                                modifier = Modifier.testTag("menu_disappearing_messages")
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(if (unreadOnlyModeEnabled) "Disable Unread-Only" else "Enable Unread-Only")
                                },
                                leadingIcon = { Icon(Icons.Default.MarkChatUnread, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    unreadOnlyModeEnabled = !unreadOnlyModeEnabled
                                    NotificationHelper.setUnreadOnlyMode(context, collectionId, unreadOnlyModeEnabled)
                                    val status = if (unreadOnlyModeEnabled) "Unread-only mode enabled" else "Unread-only mode disabled"
                                    Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.testTag("menu_unread_only_toggle")
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(if (isMuted) "Unmute Notifications" else "Mute Notifications")
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isMuted) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    isMuted = !isMuted
                                    NotificationHelper.setConversationMuted(context, collectionId, isMuted)
                                    scope.launch {
                                        firestoreRepo.setConversationMuted(collectionId, isMuted)
                                    }
                                    val status = if (isMuted) "Notifications muted" else "Notifications unmuted"
                                    Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.testTag("menu_mute_toggle")
                            )

                            DropdownMenuItem(
                                text = { Text("Clear Conversation") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showClearConfirmDialog = true
                                },
                                modifier = Modifier.testTag("menu_clear_conversation")
                            )

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text("Chat Privacy Settings") },
                                leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showPrivacyDialog = true
                                },
                                modifier = Modifier.testTag("menu_chat_privacy")
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { newText ->
                            inputText = newText
                            typingJob?.cancel()
                            typingJob = scope.launch {
                                if (newText.isNotBlank()) {
                                    firestoreRepo.setTypingStatus(collectionId, true)
                                    delay(2500L)
                                    firestoreRepo.setTypingStatus(collectionId, false)
                                } else {
                                    firestoreRepo.setTypingStatus(collectionId, false)
                                }
                            }
                        },
                        placeholder = { Text("Message...") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("entry_input"),
                        shape = MaterialTheme.shapes.extraLarge,
                        maxLines = 4
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                val text = inputText
                                inputText = ""
                                typingJob?.cancel()
                                scope.launch {
                                    try {
                                        firestoreRepo.setTypingStatus(collectionId, false)
                                        firestoreRepo.sendMessage(collectionId, currentUserId, text)
                                    } catch (_: Exception) {}
                                }
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("send_entry_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    ) { padding ->
        val listState = rememberLazyListState()

        val isImeVisible = WindowInsets.isImeVisible
        LaunchedEffect(visibleMessages.size, isImeVisible, isPartnerTyping) {
            if (visibleMessages.isNotEmpty()) {
                listState.animateScrollToItem(visibleMessages.size - 1)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // Unread-Only Mode Banner / Minimal Indicator
            if (unreadOnlyModeEnabled && !isFullHistoryRevealed) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("unread_only_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (visibleMessages.isEmpty()) "No unread entries" else "Showing unread entries only",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { isFullHistoryRevealed = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Reveal all", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            if (visibleMessages.isEmpty() && unreadOnlyModeEnabled && !isFullHistoryRevealed) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.MarkChatRead,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "All entries read",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tap the eye icon in the top bar to view history",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            items(visibleMessages, key = { it.id }) { msg ->
                val isOutgoing = msg.senderId == currentUserId
                val senderLabel = when {
                    isOutgoing -> "You"
                    senderNamesMap.containsKey(msg.senderId) && !isSelf(senderNamesMap[msg.senderId]) && senderNamesMap[msg.senderId] != "User" -> senderNamesMap[msg.senderId]!!
                    msg.senderName.isNotBlank() && !isSelf(msg.senderName) && msg.senderName != "User" -> msg.senderName
                    partnerDisplayName.isNotBlank() && !isSelf(partnerDisplayName) -> partnerDisplayName
                    else -> "Partner"
                }

                EntryCard(
                    msg = msg,
                    isOutgoing = isOutgoing,
                    senderLabel = senderLabel,
                    disappearingPolicy = disappearingPolicy,
                    partnerUid = partnerUid,
                    areReadReceiptsEnabled = areReadReceiptsEnabled,
                    onLongClick = { selectedMessageForAction = msg }
                )
            }

            if (isPartnerTyping) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$partnerDisplayName is typing...",
                                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Disappearing Messages Policy Dialog
    if (showDisappearingDialog) {
        val options = listOf(
            "once_viewed" to "Once viewed",
            "1_hour" to "1 hour after viewing",
            "24_hours" to "24 hours after viewing",
            "7_days" to "7 days after viewing",
            "never" to "Never"
        )
        var selectedOpt by remember { mutableStateOf(disappearingPolicy) }

        AlertDialog(
            onDismissRequest = { showDisappearingDialog = false },
            icon = { Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Disappearing Messages") },
            text = {
                Column {
                    Text(
                        text = "Messages in this conversation will be permanently removed according to your policy after viewing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    options.forEach { (key, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (key == selectedOpt),
                                    onClick = { selectedOpt = key },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (key == selectedOpt),
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    disappearingPolicy = selectedOpt
                    showDisappearingDialog = false
                    scope.launch {
                        firestoreRepo.updateDisappearingPolicy(collectionId, selectedOpt)
                    }
                    Toast.makeText(context, "Disappearing setting updated", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisappearingDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear Conversation Confirmation Dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clear Conversation?") },
            text = {
                Text("This will permanently delete all messages and history in this conversation for both participants.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        scope.launch {
                            firestoreRepo.clearAllMessages(collectionId)
                        }
                        Toast.makeText(context, "Conversation cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Individual Message Dialog
    selectedMessageForAction?.let { targetMsg ->
        val isMine = targetMsg.senderId == currentUserId
        AlertDialog(
            onDismissRequest = { selectedMessageForAction = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Entry") },
            text = {
                Text("Choose how you want to remove this message.")
            },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            selectedMessageForAction = null
                            scope.launch {
                                firestoreRepo.deleteMessage(collectionId, targetMsg.id, forEveryone = true)
                            }
                            Toast.makeText(context, "Message deleted", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(if (isMine) "Delete for everyone" else "Delete entry")
                    }

                    OutlinedButton(
                        onClick = {
                            selectedMessageForAction = null
                            scope.launch {
                                firestoreRepo.deleteMessage(collectionId, targetMsg.id, forEveryone = false)
                            }
                            Toast.makeText(context, "Message removed for you", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete for me")
                    }

                    TextButton(
                        onClick = { selectedMessageForAction = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel")
                    }
                }
            },
            dismissButton = {}
        )
    }

    // Chat Privacy Settings Dialog
    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            icon = { Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Chat Privacy Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("End-to-End Encryption", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text("Active • 256-bit Keystore protected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Unread-Only Privacy", fontWeight = FontWeight.SemiBold)
                            Text("Hides read history on entry", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = unreadOnlyModeEnabled,
                            onCheckedChange = {
                                unreadOnlyModeEnabled = it
                                NotificationHelper.setUnreadOnlyMode(context, collectionId, it)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Mute Notifications", fontWeight = FontWeight.SemiBold)
                            Text("Silences notifications for this chat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isMuted,
                            onCheckedChange = {
                                isMuted = it
                                NotificationHelper.setConversationMuted(context, collectionId, it)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Disappearing Timer", fontWeight = FontWeight.SemiBold)
                            val policyLabel = when (disappearingPolicy) {
                                "once_viewed" -> "Once viewed"
                                "1_hour" -> "1 hour after viewing"
                                "24_hours" -> "24 hours after viewing"
                                "7_days" -> "7 days after viewing"
                                else -> "Never"
                            }
                            Text(policyLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = {
                            showPrivacyDialog = false
                            showDisappearingDialog = true
                        }) {
                            Text("Change")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showPrivacyDialog = false }) {
                    Text("Done")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryCard(
    msg: RemoteMessage,
    isOutgoing: Boolean,
    senderLabel: String,
    disappearingPolicy: String = "never",
    partnerUid: String? = null,
    areReadReceiptsEnabled: Boolean = true,
    onLongClick: () -> Unit = {}
) {
    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val timeStr = dateFormat.format(Date(msg.createdAt))

    // Delivery & Read Status logic
    // Read: recipient UID is in readBy list (if read receipts are enabled)
    val isPartnerRead = partnerUid != null && msg.readBy.contains(partnerUid) && areReadReceiptsEnabled
    // Delivered: recipient UID is in deliveredTo or has read the message
    val isDelivered = partnerUid != null && (msg.deliveredTo.contains(partnerUid) || isPartnerRead)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick
                )
                .testTag("entry_card_${msg.id}"),
            colors = CardDefaults.cardColors(
                containerColor = if (isOutgoing) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Show sender name explicitly on incoming message entries
                if (!isOutgoing) {
                    Text(
                        text = senderLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                Text(
                    text = msg.encryptedContent,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        if (disappearingPolicy != "never") {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = "Disappearing",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }

                    if (isOutgoing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            when {
                                isPartnerRead -> {
                                    Text(
                                        text = "Read",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Icon(
                                        Icons.Default.DoneAll,
                                        contentDescription = "Read",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                isDelivered -> {
                                    Text(
                                        text = "Delivered",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Icon(
                                        Icons.Default.DoneAll,
                                        contentDescription = "Delivered",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                else -> {
                                    Text(
                                        text = "Sent",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Sent",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Received",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}
