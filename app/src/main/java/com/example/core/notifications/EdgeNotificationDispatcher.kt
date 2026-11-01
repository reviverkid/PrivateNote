package com.example.core.notifications

import android.util.Log
import com.example.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Dispatches notification triggers exclusively to the Supabase Edge Function
 * after messages are successfully written to Firestore.
 * 
 * Never includes recipient IDs, plaintext contents, or sensitive credentials.
 * The Edge Function independently validates the caller's Firebase ID token
 * and reads Firestore to determine recipients and registered FCM device tokens.
 */
object EdgeNotificationDispatcher {
    private const val TAG = "EdgeNotificationDispatcher"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val dispatcherScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // Dynamic overrides if configured at runtime
    @Volatile
    var customSupabaseUrl: String? = null

    @Volatile
    var customSupabaseAnonKey: String? = null

    fun getSupabaseUrl(): String {
        customSupabaseUrl?.takeIf { it.isNotBlank() && it != "default" }?.let { return it.trimEnd('/') }
        return try {
            val field = BuildConfig::class.java.getField("SUPABASE_URL")
            val url = (field.get(null) as? String)?.trimEnd('/') ?: ""
            if (url == "default") "" else url
        } catch (_: Exception) {
            ""
        }
    }

    fun getSupabaseAnonKey(): String {
        customSupabaseAnonKey?.takeIf { it.isNotBlank() && it != "default" }?.let { return it }
        return try {
            val field = BuildConfig::class.java.getField("SUPABASE_ANON_KEY")
            val key = (field.get(null) as? String) ?: ""
            if (key == "default") "" else key
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Dispatches notification asynchronously via Supabase Edge Function.
     * Execution is fire-and-forget on Dispatchers.IO to never block the UI or local database operations.
     */
    fun dispatchNotification(conversationId: String, messageId: String) {
        if (conversationId.isBlank() || messageId.isBlank()) return

        dispatcherScope.launch {
            try {
                val supabaseUrl = getSupabaseUrl()
                if (supabaseUrl.isBlank()) {
                    Log.d(TAG, "Edge notification skipped: SUPABASE_URL not configured. Add SUPABASE_URL to .env / secrets.")
                    return@launch
                }

                val currentUser = FirebaseAuth.getInstance().currentUser ?: run {
                    Log.w(TAG, "Cannot dispatch notification: User is not authenticated.")
                    return@launch
                }

                // Retrieve fresh Firebase ID Token
                val tokenResult = currentUser.getIdToken(false).await()
                val idToken = tokenResult.token ?: run {
                    Log.w(TAG, "Cannot dispatch notification: Failed to obtain Firebase ID token.")
                    return@launch
                }

                val payload = JSONObject().apply {
                    put("conversationId", conversationId)
                    put("messageId", messageId)
                }.toString()

                val endpoint = "$supabaseUrl/functions/v1/send-chat-notification"

                val requestBuilder = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $idToken")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))

                val anonKey = getSupabaseAnonKey()
                if (anonKey.isNotBlank()) {
                    requestBuilder.addHeader("apikey", anonKey)
                }

                val response = httpClient.newCall(requestBuilder.build()).execute()
                response.use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        Log.d(TAG, "Edge notification dispatched successfully: HTTP ${resp.code}")
                    } else {
                        Log.w(TAG, "Edge notification returned error: HTTP ${resp.code} - $respBody")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to invoke Supabase Edge Function: ${e.message}", e)
            }
        }
    }
}
