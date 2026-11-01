# Supabase Edge Function: FCM Background Push Notification Sender

This Supabase Edge Function (`send-chat-notification`) is dedicated **exclusively** to acting as a secure server-side sender for Firebase Cloud Messaging (FCM) background push notifications.

## Architecture & Data Integrity Guarantees

- **Primary Database**: Cloud Firestore remains the single source of truth for all user profiles, conversations, and end-to-end encrypted messages.
- **Authentication**: Firebase Authentication remains the sole user identity provider.
- **Presence & Status**: Firebase Realtime Database handles online presence and typing indicators.
- **Local Cache**: Room continues to provide local SQLite caching on Android devices.
- **Supabase Role**: Supabase is used **only** as a serverless execution environment for this Edge Function. No data is stored in Supabase, and no Supabase Auth/Database/Storage/Realtime services are used.
- **Privacy & Free-Tier Compatibility**: Fully compatible with the Firebase Spark free plan without requiring paid Cloud Functions for Firebase.

---

## 1. Prerequisites & Service Account Setup

To allow the Edge Function to verify records in Firestore and dispatch notifications via FCM HTTP v1:

1. Go to the [Google Cloud Console](https://console.cloud.google.com/) or [Firebase Console](https://console.firebase.google.com/).
2. Select your Firebase project (`gen-lang-client-0602929101`).
3. Navigate to **Project Settings** > **Service Accounts**.
4. Click **Generate new private key** and download the JSON file.
5. Ensure the service account has the following roles:
   - `Cloud Datastore User` (to read conversation/message records and participant device tokens in Firestore)
   - `Firebase Cloud Messaging API Admin` (to send push notifications via `https://fcm.googleapis.com/v1/projects/.../messages:send`)

---

## 2. Deploying the Edge Function to Supabase

### A. Install and Login to Supabase CLI
```bash
npm install -g supabase
supabase login
```

### B. Link your Supabase Project
```bash
supabase link --project-ref <your-supabase-project-id>
```

### C. Configure Secrets in Supabase
Set the Firebase configuration secrets in your Supabase project (do not commit secrets or service account keys into Git):

```bash
# 1. Set your Firebase Project ID
supabase secrets set FIREBASE_PROJECT_ID="gen-lang-client-0602929101"

# 2. Set your Firestore Database ID
supabase secrets set FIREBASE_DATABASE_ID="ai-studio-android-privaten-9e43141e-cbed-4fc3-8277-72bc3d8cad51"

# 3. Set the Service Account JSON (paste the entire contents of the service account JSON)
supabase secrets set FIREBASE_SERVICE_ACCOUNT_JSON='{"type":"service_account","project_id":"gen-lang-client-0602929101", ...}'
```

*(Optional: If your service account JSON contains complex characters, you can base64-encode it and pass the base64 string; the function automatically detects and decodes base64).*

### D. Deploy the Function
Deploy the function using the `--no-verify-jwt` flag (the function performs its own cryptographic verification of the caller's Firebase ID token against Google's public JWKs):

```bash
supabase functions deploy send-chat-notification --no-verify-jwt
```

---

## 3. Configuring the Android Application

In AI Studio's **Secrets Panel** (or in `.env` for local builds), configure the following values:

```properties
SUPABASE_URL=https://<your-supabase-project-id>.supabase.co
SUPABASE_ANON_KEY=<your-supabase-anon-key>
```

---

## 4. How the Flow Works

```
[Android Client: User A]
       │
       ▼ (1. Write encrypted message)
[Cloud Firestore]
       │
       ▼ (2. Write confirmed OK)
[Android Client: User A]
       │
       ▼ (3. POST /functions/v1/send-chat-notification with Firebase ID token)
[Supabase Edge Function]
       │
       ├─── (4. Cryptographically validates Firebase ID Token with Google JWKs)
       ├─── (5. Verifies sender belongs to conversation & message exists in Firestore)
       ├─── (6. Checks idempotency: notificationSent != true)
       ├─── (7. Reads recipient's registered FCM device tokens from Firestore)
       ├─── (8. Checks mute / privacy settings)
       ├─── (9. Dispatches FCM HTTP v1 notification: "New update" / "You have a new update.")
       └─── (10. Marks notificationSent = true on message in Firestore)
       │
       ▼
[FCM Server]
       │
       ▼ (High priority push)
[Android System / Device: User B] ──► Displays generic update notification
```

---

## 5. Security & Privacy Guarantees

- **No Plaintext or PII in Notifications**: Notification titles are always `"New update"` and bodies are `"You have a new update."`
- **Zero Client Trust**: The client never passes recipient IDs, recipient FCM tokens, or recipient names to the function. The function discovers participants strictly from Firestore.
- **Idempotency Protection**: Messages are marked `notificationSent: true` in Firestore to prevent duplicate notifications.
- **Automatic Token Cleanup**: If FCM returns `UNREGISTERED` or `INVALID_ARGUMENT`, the stale token is automatically removed from the recipient's Firestore profile.
