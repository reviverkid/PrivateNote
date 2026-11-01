package com.example.core.notifications

import android.util.Log
import com.example.firebase.firestore.FirestoreRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val firestoreRepo = FirestoreRepository()

    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token received: $token")
        // Securely register the updated token in the user's Firestore document
        serviceScope.launch {
            firestoreRepo.registerFcmToken(token)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received from: ${remoteMessage.from}")

        // Respect conversation mute if a conversationId tag exists in data, otherwise display generic notification
        val conversationId = remoteMessage.data["conversationId"]
        if (!conversationId.isNullOrBlank() && NotificationHelper.isConversationMuted(applicationContext, conversationId)) {
            Log.d(TAG, "Notification suppressed because conversation $conversationId is muted.")
            return
        }

        // Privacy Constraint: Strict generic title and body only
        // Never include plaintext messages, sender name, phone number, or sensitive information.
        val notificationHelper = NotificationHelper(applicationContext)
        notificationHelper.showGenericNotification(
            title = "New update",
            body = "You have a new update."
        )
    }

    companion object {
        private const val TAG = "AppFirebaseMessaging"
    }
}
