package com.example.firebase.firestore

import android.util.Log
import com.example.core.notifications.EdgeNotificationDispatcher
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class RemoteConversation(
    val id: String = "",
    val title: String = "",
    val lastEntry: String = "",
    val lastSenderId: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val participantIds: List<String> = emptyList(),
    val participantNames: Map<String, String> = emptyMap(),
    val disappearingPolicy: String = "never", // "never", "once_viewed", "1_hour", "24_hours", "7_days"
    val unreadOnlyMode: Boolean = false
)

data class UserProfile(
    val uid: String = "",
    val username: String = "",
    val userId: String = "",
    val displayName: String = ""
)

data class RemoteMessage(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val encryptedContent: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val readBy: List<String> = emptyList(),
    val viewedAt: Map<String, Long> = emptyMap(),
    val deliveredTo: List<String> = emptyList(),
    val deletedFor: List<String> = emptyList(),
    val isDeleted: Boolean = false
)

data class ContactRequest(
    val id: String = "",
    val fromUid: String = "",
    val fromUsername: String = "",
    val fromDisplayName: String = "",
    val toUid: String = "",
    val toUsername: String = "",
    val toDisplayName: String = "",
    val status: String = "pending", // "pending", "accepted", "declined"
    val conversationId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

class FirestoreRepository {
    private val firestore = FirebaseFirestore.getInstance("ai-studio-android-privaten-9e43141e-cbed-4fc3-8277-72bc3d8cad51")
    private val auth = FirebaseAuth.getInstance()

    var cachedMyProfile: Map<String, String>? = null

    suspend fun saveUserProfile(uid: String, username: String, displayName: String, customUserId: String = "") {
        val finalUserId = if (customUserId.isNotBlank()) customUserId else username
        val userMap = mapOf(
            "uid" to uid,
            "username" to username,
            "userId" to finalUserId,
            "displayName" to displayName,
            "createdAt" to System.currentTimeMillis()
        )
        try {
            firestore.collection("users").document(uid).set(userMap).await()
            val profile = mapOf("uid" to uid, "username" to username, "userId" to finalUserId, "displayName" to displayName)
            cachedMyProfile = profile
            userNameCache[uid] = displayName
            auth.currentUser?.let { user ->
                if (user.uid == uid) {
                    try {
                        val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                            .setDisplayName(displayName)
                            .build()
                        user.updateProfile(profileUpdates).await()
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreRepository", "Cloud database profile save error: ${e.message}", e)
        }
    }

    suspend fun registerFcmToken(token: String) {
        val currentUid = auth.currentUser?.uid ?: return
        if (token.isBlank()) return
        try {
            firestore.collection("users").document(currentUid).set(
                mapOf(
                    "fcmToken" to token,
                    "fcmTokens" to com.google.firebase.firestore.FieldValue.arrayUnion(token),
                    "fcmTokenUpdatedAt" to System.currentTimeMillis()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
            Log.d("FirestoreRepository", "FCM token registered successfully for user $currentUid")
        } catch (e: Exception) {
            Log.w("FirestoreRepository", "FCM token registration failed: ${e.message}", e)
        }
    }

    suspend fun getUserFcmToken(uid: String): String? {
        if (uid.isBlank()) return null
        return try {
            val doc = firestore.collection("users").document(uid).get().await()
            doc.getString("fcmToken")
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getCurrentUserProfile(): Map<String, String>? {
        val currentUid = auth.currentUser?.uid ?: return null
        cachedMyProfile?.let { return it }
        return try {
            val doc = firestore.collection("users").document(currentUid).get().await()
            val username = doc.getString("username") ?: auth.currentUser?.email?.substringBefore("@") ?: "user"
            val userId = doc.getString("userId") ?: username
            val displayName = doc.getString("displayName") ?: auth.currentUser?.displayName ?: "User"
            val profile = mapOf(
                "uid" to currentUid,
                "username" to username,
                "userId" to userId,
                "displayName" to displayName
            )
            cachedMyProfile = profile
            userNameCache[currentUid] = displayName
            if (auth.currentUser?.displayName.isNullOrBlank() && displayName.isNotBlank() && displayName != "User") {
                try {
                    val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                        .setDisplayName(displayName)
                        .build()
                    auth.currentUser?.updateProfile(profileUpdates)
                } catch (_: Exception) {}
            }
            profile
        } catch (e: Exception) {
            val fallbackUsername = auth.currentUser?.email?.substringBefore("@") ?: "user"
            val fallback = mapOf(
                "uid" to currentUid,
                "username" to fallbackUsername,
                "userId" to fallbackUsername,
                "displayName" to (auth.currentUser?.displayName ?: "User")
            )
            cachedMyProfile = fallback
            fallback
        }
    }

    suspend fun getUserProfile(uid: String): Map<String, String>? {
        return try {
            val doc = firestore.collection("users").document(uid).get().await()
            mapOf(
                "uid" to uid,
                "username" to (doc.getString("username") ?: "user"),
                "displayName" to (doc.getString("displayName") ?: "User")
            )
        } catch (e: Exception) {
            null
        }
    }

    val userNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun resolveUserName(uid: String): String {
        if (uid.isBlank()) return "User"
        userNameCache[uid]?.let { if (it.isNotBlank() && it != "User") return it }
        val profile = getUserProfile(uid)
        var name = profile?.get("displayName")?.takeIf { it.isNotBlank() }
            ?: profile?.get("username")?.takeIf { it.isNotBlank() }

        if (name.isNullOrBlank() || name == "User") {
            try {
                val reqSnapshot = firestore.collection("contactRequests")
                    .whereEqualTo("fromUid", uid)
                    .limit(1)
                    .get().await()
                val reqDoc = reqSnapshot.documents.firstOrNull()
                if (reqDoc != null) {
                    name = reqDoc.getString("fromDisplayName")?.takeIf { it.isNotBlank() }
                        ?: reqDoc.getString("fromUsername")
                }
            } catch (_: Exception) {}
        }
        if (name.isNullOrBlank() || name == "User") {
            try {
                val reqSnapshot = firestore.collection("contactRequests")
                    .whereEqualTo("toUid", uid)
                    .limit(1)
                    .get().await()
                val reqDoc = reqSnapshot.documents.firstOrNull()
                if (reqDoc != null) {
                    name = reqDoc.getString("toDisplayName")?.takeIf { it.isNotBlank() }
                        ?: reqDoc.getString("toUsername")
                }
            } catch (_: Exception) {}
        }

        val finalName = name ?: "User"
        if (finalName.isNotBlank() && finalName != "User") {
            userNameCache[uid] = finalName
        }
        return finalName
    }

    fun getConversationsFlow(): Flow<List<RemoteConversation>> = callbackFlow {
        val currentUserId = auth.currentUser?.uid
        if (currentUserId == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = firestore.collection("conversations")
            .whereArrayContains("participantIds", currentUserId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.map { doc ->
                    val rawParticipantIds = doc.get("participantIds") as? List<*>
                    val participantIds = rawParticipantIds?.mapNotNull { it?.toString() } ?: emptyList()
                    val participantNames: MutableMap<String, String> = mutableMapOf()
                    val participantNamesRaw = doc.get("participantNames") as? Map<*, *>
                    participantNamesRaw?.forEach { (k, v) ->
                        if (k != null && v != null) {
                            participantNames[k.toString()] = v.toString()
                        }
                    }

                    val otherUid = participantIds.firstOrNull { it != currentUserId }
                    var otherName: String? = if (!otherUid.isNullOrBlank()) participantNames[otherUid] else null
                    if (otherName.isNullOrBlank() && !otherUid.isNullOrBlank() && userNameCache.containsKey(otherUid)) {
                        otherName = userNameCache[otherUid]
                    }

                    val rawTitle = doc.getString("title") ?: "Secure Record"
                    val myProfile = cachedMyProfile
                    val myDisplayName = myProfile?.get("displayName") ?: auth.currentUser?.displayName ?: ""
                    val myUsername = myProfile?.get("username") ?: ""
                    val myEmailName = auth.currentUser?.email?.substringBefore("@") ?: ""

                    fun isSelf(n: String?): Boolean {
                        if (n.isNullOrBlank()) return false
                        val cleanName = n.removePrefix("Record: ").trim()
                        return (myDisplayName.isNotBlank() && cleanName.equals(myDisplayName, ignoreCase = true)) ||
                                (myUsername.isNotBlank() && cleanName.equals(myUsername, ignoreCase = true)) ||
                                (myEmailName.isNotBlank() && cleanName.equals(myEmailName, ignoreCase = true)) ||
                                (myDisplayName.isNotBlank() && cleanName.contains(myDisplayName, ignoreCase = true))
                    }

                    val resolvedTitle = when {
                        !otherName.isNullOrBlank() && !isSelf(otherName) -> "Record: $otherName"
                        isSelf(rawTitle) -> {
                            if (!otherName.isNullOrBlank() && !isSelf(otherName)) "Record: $otherName"
                            else "Secure Record"
                        }
                        else -> rawTitle
                    }

                    RemoteConversation(
                        id = doc.id,
                        title = resolvedTitle,
                        lastEntry = doc.getString("lastEntry") ?: "No entries yet",
                        lastSenderId = doc.getString("lastSenderId") ?: "",
                        updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis(),
                        participantIds = participantIds,
                        participantNames = participantNames,
                        disappearingPolicy = doc.getString("disappearingPolicy") ?: "never",
                        unreadOnlyMode = doc.getBoolean("unreadOnlyMode") ?: false
                    )
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    fun getMessagesFlow(conversationId: String): Flow<List<RemoteMessage>> = callbackFlow {
        val listener = firestore.collection("conversations")
            .document(conversationId)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { doc ->
                    val isDeleted = doc.getBoolean("isDeleted") ?: false
                    val deletedForRaw = doc.get("deletedFor") as? List<*>
                    val deletedFor = deletedForRaw?.mapNotNull { it?.toString() } ?: emptyList()
                    val readByRaw = doc.get("readBy") as? List<*>
                    val readBy = readByRaw?.mapNotNull { it?.toString() } ?: emptyList()
                    val deliveredToRaw = doc.get("deliveredTo") as? List<*>
                    val deliveredTo = deliveredToRaw?.mapNotNull { it?.toString() } ?: emptyList()
                    val viewedAtRaw = doc.get("viewedAt") as? Map<*, *>
                    val viewedAt = viewedAtRaw?.mapNotNull { (k, v) ->
                        if (k != null && v is Number) k.toString() to v.toLong() else null
                    }?.toMap() ?: emptyMap()

                    RemoteMessage(
                        id = doc.id,
                        senderId = doc.getString("senderId") ?: "",
                        senderName = doc.getString("senderName") ?: "",
                        encryptedContent = doc.getString("encryptedContent") ?: "",
                        createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                        readBy = readBy,
                        viewedAt = viewedAt,
                        deliveredTo = deliveredTo,
                        deletedFor = deletedFor,
                        isDeleted = isDeleted
                    )
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    suspend fun getConversationDetails(conversationId: String): RemoteConversation? {
        return try {
            val doc = firestore.collection("conversations").document(conversationId).get().await()
            val currentUserId = auth.currentUser?.uid ?: ""
            val myProfile = getCurrentUserProfile()
            val myDisplayName = myProfile?.get("displayName") ?: auth.currentUser?.displayName ?: ""
            val myUsername = myProfile?.get("username") ?: ""
            val myEmailName = auth.currentUser?.email?.substringBefore("@") ?: ""

            fun isSelf(n: String?): Boolean {
                if (n.isNullOrBlank()) return false
                val cleanName = n.removePrefix("Record: ").trim()
                return (myDisplayName.isNotBlank() && cleanName.equals(myDisplayName, ignoreCase = true)) ||
                        (myUsername.isNotBlank() && cleanName.equals(myUsername, ignoreCase = true)) ||
                        (myEmailName.isNotBlank() && cleanName.equals(myEmailName, ignoreCase = true)) ||
                        (myDisplayName.isNotBlank() && cleanName.contains(myDisplayName, ignoreCase = true))
            }

            val rawParticipantIds = doc.get("participantIds") as? List<*>
            val participantIds = rawParticipantIds?.mapNotNull { it?.toString() }?.toMutableList() ?: mutableListOf()
            val participantNamesRaw = doc.get("participantNames") as? Map<*, *>
            val participantNames: MutableMap<String, String> = mutableMapOf()
            participantNamesRaw?.forEach { (k, v) ->
                if (k != null && v != null) {
                    participantNames[k.toString()] = v.toString()
                }
            }

            var otherUid = participantIds.firstOrNull { it != currentUserId }

            // If participantIds didn't contain otherUid, inspect messages or contactRequests
            if (otherUid.isNullOrBlank()) {
                val msgs = firestore.collection("conversations").document(conversationId)
                    .collection("messages").limit(10).get().await()
                val otherMsg = msgs.documents.firstOrNull { (it.getString("senderId") ?: "") != currentUserId && (it.getString("senderId") ?: "").isNotBlank() }
                if (otherMsg != null) {
                    otherUid = otherMsg.getString("senderId")
                    if (otherUid != null && !participantIds.contains(otherUid)) {
                        participantIds.add(otherUid)
                    }
                }
            }

            // Check contactRequests if still null
            if (otherUid.isNullOrBlank()) {
                try {
                    val reqSnapshot = firestore.collection("contactRequests")
                        .whereEqualTo("conversationId", conversationId)
                        .limit(1)
                        .get().await()
                    val reqDoc = reqSnapshot.documents.firstOrNull()
                    if (reqDoc != null) {
                        val from = reqDoc.getString("fromUid") ?: ""
                        val to = reqDoc.getString("toUid") ?: ""
                        otherUid = if (from == currentUserId) to else from
                        if (otherUid.isNotBlank() && !participantIds.contains(otherUid)) {
                            participantIds.add(otherUid)
                        }
                    }
                } catch (_: Exception) {}
            }

            var otherName: String? = if (!otherUid.isNullOrBlank()) participantNames[otherUid] else null
            if ((otherName.isNullOrBlank() || isSelf(otherName)) && !otherUid.isNullOrBlank()) {
                val resolved = resolveUserName(otherUid)
                if (resolved.isNotBlank() && resolved != "User" && !isSelf(resolved)) {
                    otherName = resolved
                    participantNames[otherUid] = resolved
                    try {
                        firestore.collection("conversations").document(conversationId).update(
                            mapOf(
                                "participantNames" to participantNames,
                                "participantIds" to participantIds
                            )
                        )
                    } catch (_: Exception) {}
                }
            }

            val rawTitle = doc.getString("title") ?: "Secure Record"
            val resolvedTitle = when {
                !otherName.isNullOrBlank() && !isSelf(otherName) -> "Record: $otherName"
                isSelf(rawTitle) -> {
                    if (!otherName.isNullOrBlank() && !isSelf(otherName)) "Record: $otherName"
                    else "Secure Record"
                }
                else -> rawTitle
            }

            RemoteConversation(
                id = doc.id,
                title = resolvedTitle,
                lastEntry = doc.getString("lastEntry") ?: "",
                lastSenderId = doc.getString("lastSenderId") ?: "",
                updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis(),
                participantIds = participantIds,
                participantNames = participantNames,
                disappearingPolicy = doc.getString("disappearingPolicy") ?: "never",
                unreadOnlyMode = doc.getBoolean("unreadOnlyMode") ?: false
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun updateDisappearingPolicy(conversationId: String, policy: String) {
        try {
            firestore.collection("conversations").document(conversationId).update(
                mapOf("disappearingPolicy" to policy)
            ).await()
        } catch (e: Exception) {
            Log.w("FirestoreRepository", "Update disappearing policy error: ${e.message}", e)
        }
    }

    suspend fun setConversationMuted(conversationId: String, isMuted: Boolean) {
        val currentUid = auth.currentUser?.uid ?: return
        try {
            val userRef = firestore.collection("users").document(currentUid)
            if (isMuted) {
                userRef.update("mutedConversations", com.google.firebase.firestore.FieldValue.arrayUnion(conversationId)).await()
            } else {
                userRef.update("mutedConversations", com.google.firebase.firestore.FieldValue.arrayRemove(conversationId)).await()
            }
        } catch (e: Exception) {
            Log.w("FirestoreRepository", "Set conversation muted error: ${e.message}", e)
        }
    }

    suspend fun setGlobalNotificationsEnabled(enabled: Boolean) {
        val currentUid = auth.currentUser?.uid ?: return
        try {
            firestore.collection("users").document(currentUid).update(
                mapOf("notificationsEnabled" to enabled)
            ).await()
        } catch (e: Exception) {
            Log.w("FirestoreRepository", "Set global notifications error: ${e.message}", e)
        }
    }

    suspend fun deleteMessage(conversationId: String, messageId: String, forEveryone: Boolean) {
        val currentUid = auth.currentUser?.uid ?: return
        try {
            if (forEveryone) {
                firestore.collection("conversations").document(conversationId)
                    .collection("messages").document(messageId).delete().await()
            } else {
                firestore.collection("conversations").document(conversationId)
                    .collection("messages").document(messageId).update(
                        "deletedFor", com.google.firebase.firestore.FieldValue.arrayUnion(currentUid)
                    ).await()
            }
        } catch (e: Exception) {
            Log.w("FirestoreRepository", "Delete message error: ${e.message}", e)
        }
    }

    suspend fun clearAllMessages(conversationId: String) {
        try {
            val messagesSnapshot = firestore.collection("conversations").document(conversationId)
                .collection("messages").get().await()
            val batch = firestore.batch()
            messagesSnapshot.documents.forEach { doc ->
                batch.delete(doc.reference)
            }
            batch.update(
                firestore.collection("conversations").document(conversationId),
                mapOf(
                    "lastEntry" to "Conversation cleared",
                    "updatedAt" to System.currentTimeMillis()
                )
            )
            batch.commit().await()
        } catch (e: Exception) {
            Log.w("FirestoreRepository", "Clear conversation error: ${e.message}", e)
        }
    }

    suspend fun markMessagesAsViewed(conversationId: String, messageIds: List<String>) {
        val currentUid = auth.currentUser?.uid ?: return
        if (messageIds.isEmpty()) return
        val now = System.currentTimeMillis()
        try {
            val batch = firestore.batch()
            messageIds.take(20).forEach { msgId ->
                val ref = firestore.collection("conversations").document(conversationId)
                    .collection("messages").document(msgId)
                batch.update(ref, "readBy", com.google.firebase.firestore.FieldValue.arrayUnion(currentUid))
                batch.update(ref, "viewedAt.$currentUid", now)
            }
            batch.commit().await()
        } catch (_: Exception) {}
    }

    suspend fun markMessagesAsDelivered(conversationId: String, messageIds: List<String>) {
        val currentUid = auth.currentUser?.uid ?: return
        if (messageIds.isEmpty()) return
        try {
            val batch = firestore.batch()
            messageIds.take(20).forEach { msgId ->
                val ref = firestore.collection("conversations").document(conversationId)
                    .collection("messages").document(msgId)
                batch.update(ref, "deliveredTo", com.google.firebase.firestore.FieldValue.arrayUnion(currentUid))
            }
            batch.commit().await()
        } catch (_: Exception) {}
    }

    companion object {
        fun generateSecureUserId(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = java.security.SecureRandom()
            val sb = StringBuilder("usr_")
            for (i in 0 until 8) {
                sb.append(chars[rnd.nextInt(chars.length)])
            }
            return sb.toString()
        }
    }

    fun generateSecureUserId(): String = Companion.generateSecureUserId()

    private val searchTimestamps = mutableListOf<Long>()

    suspend fun searchUserByExactId(exactId: String): Result<UserProfile?> {
        val trimmed = exactId.trim()
        if (trimmed.length < 8) {
            return Result.failure(IllegalArgumentException("User ID must be at least 8 characters."))
        }
        val now = System.currentTimeMillis()
        synchronized(searchTimestamps) {
            searchTimestamps.removeAll { now - it > 30000L }
            if (searchTimestamps.size >= 5) {
                return Result.failure(IllegalStateException("Search rate limit reached. Please wait 30 seconds."))
            }
            searchTimestamps.add(now)
        }

        val currentUid = auth.currentUser?.uid
        return try {
            // 1. Exact UID match
            val docByUid = firestore.collection("users").document(trimmed).get().await()
            if (docByUid.exists() && docByUid.id != currentUid) {
                val profile = UserProfile(
                    uid = docByUid.id,
                    username = docByUid.getString("userId") ?: docByUid.getString("username") ?: docByUid.id,
                    userId = docByUid.getString("userId") ?: docByUid.getString("username") ?: docByUid.id,
                    displayName = docByUid.getString("displayName") ?: "User"
                )
                return Result.success(profile)
            }

            // 2. Exact match by userId field
            val qUserId = firestore.collection("users")
                .whereEqualTo("userId", trimmed)
                .limit(1)
                .get().await()
            val docUserId = qUserId.documents.firstOrNull()
            if (docUserId != null && docUserId.id != currentUid) {
                val profile = UserProfile(
                    uid = docUserId.id,
                    username = docUserId.getString("userId") ?: docUserId.getString("username") ?: docUserId.id,
                    userId = docUserId.getString("userId") ?: docUserId.getString("username") ?: docUserId.id,
                    displayName = docUserId.getString("displayName") ?: "User"
                )
                return Result.success(profile)
            }

            // 3. Exact match by username field (backward compatibility for existing accounts)
            val qUsername = firestore.collection("users")
                .whereEqualTo("username", trimmed)
                .limit(1)
                .get().await()
            val docUsername = qUsername.documents.firstOrNull()
            if (docUsername != null && docUsername.id != currentUid) {
                val profile = UserProfile(
                    uid = docUsername.id,
                    username = docUsername.getString("userId") ?: docUsername.getString("username") ?: docUsername.id,
                    userId = docUsername.getString("userId") ?: docUsername.getString("username") ?: docUsername.id,
                    displayName = docUsername.getString("displayName") ?: "User"
                )
                return Result.success(profile)
            }

            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateCustomUserId(newUserId: String): Result<Unit> {
        val currentUid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not authenticated"))
        val trimmed = newUserId.trim()
        if (trimmed.length < 8) {
            return Result.failure(IllegalArgumentException("User ID must be at least 8 characters."))
        }
        if (!trimmed.matches(Regex("^[a-zA-Z0-9_-]{8,32}$"))) {
            return Result.failure(IllegalArgumentException("User ID can only contain letters, numbers, underscores, and dashes."))
        }

        return try {
            val q1 = firestore.collection("users").whereEqualTo("userId", trimmed).limit(1).get().await()
            if (q1.documents.any { it.id != currentUid }) {
                return Result.failure(IllegalStateException("This User ID is already taken."))
            }
            val q2 = firestore.collection("users").whereEqualTo("username", trimmed).limit(1).get().await()
            if (q2.documents.any { it.id != currentUid }) {
                return Result.failure(IllegalStateException("This User ID is already taken."))
            }

            firestore.collection("users").document(currentUid).update(
                mapOf("userId" to trimmed, "username" to trimmed)
            ).await()
            cachedMyProfile?.let {
                val updated = it.toMutableMap()
                updated["userId"] = trimmed
                updated["username"] = trimmed
                cachedMyProfile = updated
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createConversation(title: String, recipientUid: String, recipientName: String = ""): String {
        val currentUserId = auth.currentUser?.uid ?: throw IllegalStateException("Not authenticated")
        val currentProfile = getCurrentUserProfile()
        val currentUserName = currentProfile?.get("displayName") ?: "User"
        val participantNames = mapOf(
            currentUserId to currentUserName,
            recipientUid to recipientName.ifBlank { "User" }
        )
        val conversationMap = mapOf(
            "title" to title,
            "lastEntry" to "Collection created",
            "lastSenderId" to currentUserId,
            "updatedAt" to System.currentTimeMillis(),
            "participantIds" to listOf(currentUserId, recipientUid),
            "participantNames" to participantNames
        )
        val docRef = firestore.collection("conversations").add(conversationMap).await()
        return docRef.id
    }

    suspend fun sendMessage(conversationId: String, senderId: String, encryptedContent: String) {
        val currentProfile = getCurrentUserProfile()
        val senderName = currentProfile?.get("displayName") ?: "User"

        val messageMap = mapOf(
            "senderId" to senderId,
            "senderName" to senderName,
            "encryptedContent" to encryptedContent,
            "createdAt" to System.currentTimeMillis(),
            "deliveryState" to "sent"
        )
        try {
            val messageRef = firestore.collection("conversations")
                .document(conversationId)
                .collection("messages")
                .add(messageMap)
                .await()

            firestore.collection("conversations")
                .document(conversationId)
                .update(
                    mapOf(
                        "lastEntry" to encryptedContent,
                        "lastSenderId" to senderId,
                        "updatedAt" to System.currentTimeMillis()
                    )
                )
                .await()

            // Asynchronously invoke Supabase Edge Function to deliver background FCM notification
            EdgeNotificationDispatcher.dispatchNotification(conversationId, messageRef.id)
        } catch (e: Exception) {
            Log.w("FirestoreRepository", "Send message error: ${e.message}", e)
        }
    }

    suspend fun sendConnectionRequest(toUid: String, toUsername: String, toDisplayName: String): Boolean {
        val currentProfile = getCurrentUserProfile() ?: return false
        val fromUid = currentProfile["uid"] ?: return false
        val fromUsername = currentProfile["username"] ?: "user"
        val fromDisplayName = currentProfile["displayName"] ?: "User"

        val requestId = "${fromUid}_${toUid}"
        val requestMap = mapOf(
            "id" to requestId,
            "fromUid" to fromUid,
            "fromUsername" to fromUsername,
            "fromDisplayName" to fromDisplayName,
            "toUid" to toUid,
            "toUsername" to toUsername,
            "toDisplayName" to toDisplayName,
            "status" to "pending",
            "conversationId" to null,
            "createdAt" to System.currentTimeMillis()
        )

        return try {
            firestore.collection("contactRequests").document(requestId).set(requestMap).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreRepository", "Send connection request error: ${e.message}", e)
            false
        }
    }

    fun getRequestsFlow(): Flow<List<ContactRequest>> = callbackFlow {
        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = firestore.collection("contactRequests")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { doc ->
                    val fromUid = doc.getString("fromUid") ?: ""
                    val toUid = doc.getString("toUid") ?: ""
                    if (fromUid == currentUid || toUid == currentUid) {
                        ContactRequest(
                            id = doc.id,
                            fromUid = fromUid,
                            fromUsername = doc.getString("fromUsername") ?: "",
                            fromDisplayName = doc.getString("fromDisplayName") ?: "",
                            toUid = toUid,
                            toUsername = doc.getString("toUsername") ?: "",
                            toDisplayName = doc.getString("toDisplayName") ?: "",
                            status = doc.getString("status") ?: "pending",
                            conversationId = doc.getString("conversationId"),
                            createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                        )
                    } else null
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    suspend fun acceptConnectionRequest(request: ContactRequest): String? {
        val currentUid = auth.currentUser?.uid ?: return null
        val otherUid = if (request.fromUid == currentUid) request.toUid else request.fromUid
        val otherName = if (request.fromUid == currentUid) request.toDisplayName else request.fromDisplayName

        return try {
            val convId = request.conversationId ?: run {
                val participantNames = mapOf(
                    request.fromUid to request.fromDisplayName,
                    request.toUid to request.toDisplayName
                )
                val conversationMap = mapOf(
                    "title" to "Record: $otherName",
                    "lastEntry" to "Connection accepted. Secure record initialized.",
                    "lastSenderId" to currentUid,
                    "updatedAt" to System.currentTimeMillis(),
                    "participantIds" to listOf(request.fromUid, request.toUid),
                    "participantNames" to participantNames
                )
                val docRef = firestore.collection("conversations").add(conversationMap).await()
                docRef.id
            }

            firestore.collection("contactRequests").document(request.id).update(
                mapOf(
                    "status" to "accepted",
                    "conversationId" to convId,
                    "updatedAt" to System.currentTimeMillis()
                )
            ).await()

            convId
        } catch (e: Exception) {
            Log.w("FirestoreRepository", "Accept request error: ${e.message}", e)
            null
        }
    }

    suspend fun declineConnectionRequest(requestId: String) {
        try {
            firestore.collection("contactRequests").document(requestId).update(
                mapOf(
                    "status" to "declined",
                    "updatedAt" to System.currentTimeMillis()
                )
            ).await()
        } catch (e: Exception) {
            Log.w("FirestoreRepository", "Decline request error: ${e.message}", e)
        }
    }

    suspend fun setTypingStatus(conversationId: String, isTyping: Boolean) {
        val currentUid = auth.currentUser?.uid ?: return
        try {
            firestore.collection("conversations").document(conversationId).update(
                "typing.$currentUid", if (isTyping) System.currentTimeMillis() else 0L
            ).await()
        } catch (_: Exception) {}
    }

    fun getConversationSnapshotFlow(conversationId: String): Flow<Map<String, Any>?> = callbackFlow {
        val listener = firestore.collection("conversations").document(conversationId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snapshot?.data)
            }
        awaitClose { listener.remove() }
    }

    suspend fun updateUserPresence(isOnline: Boolean = true) {
        val currentUid = auth.currentUser?.uid ?: return
        try {
            firestore.collection("users").document(currentUid).update(
                mapOf(
                    "lastActive" to System.currentTimeMillis(),
                    "isOnline" to isOnline
                )
            )
        } catch (_: Exception) {}
    }

    fun getUserPresenceFlow(uid: String): Flow<Pair<Boolean, Long>> = callbackFlow {
        if (uid.isBlank()) {
            trySend(Pair(false, 0L))
            close()
            return@callbackFlow
        }
        val listener = firestore.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    trySend(Pair(false, 0L))
                    return@addSnapshotListener
                }
                val isOnline = snapshot.getBoolean("isOnline") ?: false
                val lastActive = snapshot.getLong("lastActive") ?: 0L
                trySend(Pair(isOnline, lastActive))
            }
        awaitClose { listener.remove() }
    }
}
