package com.example.core.notifications

import android.content.Context
import android.util.Log
import com.example.firebase.firestore.FirestoreRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

object FcmTokenManager {
    private const val TAG = "FcmTokenManager"
    private const val PREFS_NAME = "fcm_token_prefs"
    private const val KEY_LAST_SYNCED_TOKEN = "last_synced_token"
    private const val KEY_LAST_SYNCED_UID = "last_synced_uid"

    suspend fun syncFcmToken(context: Context, firestoreRepo: FirestoreRepository = FirestoreRepository()) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isNullOrBlank()) {
                Log.w(TAG, "Retrieved FCM token is empty.")
                return
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastSyncedToken = prefs.getString(KEY_LAST_SYNCED_TOKEN, null)
            val lastSyncedUid = prefs.getString(KEY_LAST_SYNCED_UID, null)

            // Register if token or authenticated user changed
            if (token != lastSyncedToken || currentUid != lastSyncedUid) {
                firestoreRepo.registerFcmToken(token)
                prefs.edit()
                    .putString(KEY_LAST_SYNCED_TOKEN, token)
                    .putString(KEY_LAST_SYNCED_UID, currentUid)
                    .apply()
                Log.d(TAG, "FCM token synced successfully for user: $currentUid")
            } else {
                Log.d(TAG, "FCM token is already up to date.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error synchronizing FCM token: ${e.message}", e)
        }
    }
}
