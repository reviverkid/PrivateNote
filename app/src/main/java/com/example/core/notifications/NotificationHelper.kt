package com.example.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity

class NotificationHelper(private val context: Context) {
    private val channelId = "private_workspace_messages"
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Private Notes Updates"
            val descriptionText = "Notifications for new records, entries, and connection updates"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun showGenericNotification(
        title: String = "New update",
        body: String = "You have a new update."
    ) {
        if (!hasNotificationPermission()) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastNotificationTime < 1500L) {
            return
        }
        lastNotificationTime = now

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Strict privacy constraint: Always use generic notification copy
        val safeTitle = "New update"
        val safeBody = "You have a new update."

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(safeTitle)
            .setContentText(safeBody)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationId = 1001
        notificationManager.notify(notificationId, builder.build())
    }

    companion object {
        private var lastNotificationTime: Long = 0L
        private const val PREFS_NAME = "private_workspace_chat_prefs"
        private const val MUTED_CONVERSATIONS_KEY = "muted_conversations"

        fun isConversationMuted(context: Context, conversationId: String): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mutedSet = prefs.getStringSet(MUTED_CONVERSATIONS_KEY, emptySet()) ?: emptySet()
            return mutedSet.contains(conversationId)
        }

        fun setConversationMuted(context: Context, conversationId: String, isMuted: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mutedSet = prefs.getStringSet(MUTED_CONVERSATIONS_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (isMuted) {
                mutedSet.add(conversationId)
            } else {
                mutedSet.remove(conversationId)
            }
            prefs.edit().putStringSet(MUTED_CONVERSATIONS_KEY, mutedSet).apply()
        }

        fun isUnreadOnlyMode(context: Context, conversationId: String): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean("unread_only_$conversationId", false)
        }

        fun setUnreadOnlyMode(context: Context, conversationId: String, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean("unread_only_$conversationId", enabled).apply()
        }

        fun areReadReceiptsEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean("read_receipts_enabled", true)
        }

        fun setReadReceiptsEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean("read_receipts_enabled", enabled).apply()
        }
    }
}
