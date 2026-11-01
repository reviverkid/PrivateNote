# ProGuard & R8 configuration for production release

# Preserve line numbers and source file attributes for crash analysis
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Firebase components
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Room Database models and DAOs
-keep class com.example.data.local.** { *; }
-dontwarn androidx.room.**

# Firestore data models
-keep class com.example.firebase.firestore.RemoteConversation { *; }
-keep class com.example.firebase.firestore.RemoteMessage { *; }
-keep class com.example.firebase.firestore.UserProfile { *; }
-keep class com.example.firebase.firestore.ContactRequest { *; }

# Core and notifications
-keep class com.example.core.notifications.** { *; }

# OkHttp & Coroutines
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn kotlinx.coroutines.**

# Preserve BuildConfig
-keep class com.example.BuildConfig { *; }
