// Supabase Edge Function: send-chat-notification
// Server-side FCM push notification sender for Firebase Cloud Messaging.
// Primary database remains Cloud Firestore; no user or chat data is persisted in Supabase.

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

// In-memory caches for Google JWKS public keys and OAuth2 access token
let cachedJwks: { keys: any[]; expiresAt: number } | null = null;
let cachedGoogleToken: { token: string; expiresAt: number } | null = null;

// Base64URL Helpers
function base64UrlDecode(input: string): Uint8Array {
  let base64 = input.replace(/-/g, "+").replace(/_/g, "/");
  while (base64.length % 4 !== 0) {
    base64 += "=";
  }
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

function stringToBase64Url(str: string): string {
  return base64UrlEncode(new TextEncoder().encode(str));
}

// 1. Cryptographically Secure Firebase ID Token Verification
async function verifyFirebaseIdToken(
  token: string,
  expectedProjectId: string
): Promise<{ uid: string }> {
  if (!token || typeof token !== "string") {
    throw new Error("Missing token");
  }

  const parts = token.split(".");
  if (parts.length !== 3) {
    throw new Error("Malformed JWT format");
  }

  const [headerB64, payloadB64, signatureB64] = parts;

  let header: any;
  let payload: any;
  try {
    header = JSON.parse(new TextDecoder().decode(base64UrlDecode(headerB64)));
    payload = JSON.parse(new TextDecoder().decode(base64UrlDecode(payloadB64)));
  } catch (_e) {
    throw new Error("Malformed JWT components");
  }

  // Algorithm check: Must be RS256
  if (header.alg !== "RS256") {
    throw new Error("Invalid algorithm: expected RS256");
  }
  if (!header.kid || typeof header.kid !== "string") {
    throw new Error("Missing kid in token header");
  }

  // Claim checks
  const now = Math.floor(Date.now() / 1000);

  if (typeof payload.exp !== "number" || payload.exp <= now) {
    throw new Error("Token expired");
  }

  // Allow up to 300s clock skew for iat and auth_time
  if (typeof payload.iat !== "number" || payload.iat > now + 300) {
    throw new Error("Token issued in the future");
  }

  if (payload.auth_time && (typeof payload.auth_time !== "number" || payload.auth_time > now + 300)) {
    throw new Error("Invalid auth_time in token");
  }

  const expectedIssuer = `https://securetoken.google.com/${expectedProjectId}`;
  if (payload.iss !== expectedIssuer) {
    throw new Error("Token issuer mismatch");
  }

  if (payload.aud !== expectedProjectId) {
    throw new Error("Token audience mismatch");
  }

  const uid = payload.sub || payload.user_id;
  if (!uid || typeof uid !== "string" || uid.length === 0 || uid.length > 128) {
    throw new Error("Invalid subject/user_id claim");
  }

  // Fetch or refresh Google JWKS
  const jwkUrl = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
  let keyDef = cachedJwks && cachedJwks.expiresAt > Date.now()
    ? cachedJwks.keys.find((k: any) => k.kid === header.kid)
    : null;

  if (!keyDef) {
    const res = await fetch(jwkUrl);
    if (!res.ok) {
      throw new Error("Failed to fetch Google verification keys");
    }

    // Parse Cache-Control header
    const cacheControl = res.headers.get("cache-control") || "";
    const maxAgeMatch = cacheControl.match(/max-age=(\d+)/);
    const ttlSeconds = maxAgeMatch ? parseInt(maxAgeMatch[1], 10) : 3600;

    const data = await res.json();
    cachedJwks = {
      keys: data.keys || [],
      expiresAt: Date.now() + ttlSeconds * 1000,
    };

    keyDef = cachedJwks.keys.find((k: any) => k.kid === header.kid);
  }

  if (!keyDef) {
    throw new Error("Unknown key ID in token header");
  }

  // Import Public Key
  const cryptoKey = await crypto.subtle.importKey(
    "jwk",
    keyDef,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"]
  );

  // Verify Signature
  const signedData = new TextEncoder().encode(`${headerB64}.${payloadB64}`);
  const signatureBytes = base64UrlDecode(signatureB64);

  const isValid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    signatureBytes,
    signedData
  );

  if (!isValid) {
    throw new Error("Token signature verification failed");
  }

  return { uid };
}

// 2. Google Service Account Token Generation
function pemToArrayBuffer(pem: string): ArrayBuffer {
  const cleanPem = pem
    .replace(/-----BEGIN[A-Z\s]+-----/g, "")
    .replace(/-----END[A-Z\s]+-----/g, "")
    .replace(/\s+/g, "");
  const binary = atob(cleanPem);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

async function getGoogleOAuth2Token(serviceAccount: {
  client_email: string;
  private_key: string;
  token_uri?: string;
}): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedGoogleToken && cachedGoogleToken.expiresAt > now + 60) {
    return cachedGoogleToken.token;
  }

  const tokenUri = serviceAccount.token_uri || "https://oauth2.googleapis.com/token";
  const header = { alg: "RS256", typ: "JWT" };
  const payload = {
    iss: serviceAccount.client_email,
    sub: serviceAccount.client_email,
    aud: tokenUri,
    iat: now,
    exp: now + 3600,
    scope: "https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase.messaging",
  };

  const headerB64 = stringToBase64Url(JSON.stringify(header));
  const payloadB64 = stringToBase64Url(JSON.stringify(payload));
  const dataToSign = new TextEncoder().encode(`${headerB64}.${payloadB64}`);

  const privateKeyBuffer = pemToArrayBuffer(serviceAccount.private_key);
  const signingKey = await crypto.subtle.importKey(
    "pkcs8",
    privateKeyBuffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signatureBuffer = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    signingKey,
    dataToSign
  );
  const signatureB64 = base64UrlEncode(new Uint8Array(signatureBuffer));
  const jwtAssertion = `${headerB64}.${payloadB64}.${signatureB64}`;

  const tokenRes = await fetch(tokenUri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwtAssertion,
    }),
  });

  if (!tokenRes.ok) {
    throw new Error(`Google OAuth2 token exchange failed with status ${tokenRes.status}`);
  }

  const tokenData = await tokenRes.json();
  const accessToken = tokenData.access_token;
  const expiresIn = tokenData.expires_in || 3600;

  cachedGoogleToken = {
    token: accessToken,
    expiresAt: now + expiresIn,
  };

  return accessToken;
}

// 3. Firestore REST API Helpers
function getFirestoreBaseUrl(projectId: string, databaseId: string): string {
  const db = databaseId || "(default)";
  return `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${db}/documents`;
}

// Helper to convert Firestore JSON values to JS values
function parseFirestoreValue(val: any): any {
  if (!val) return null;
  if ("stringValue" in val) return val.stringValue;
  if ("booleanValue" in val) return val.booleanValue;
  if ("integerValue" in val) return parseInt(val.integerValue, 10);
  if ("doubleValue" in val) return val.doubleValue;
  if ("arrayValue" in val) return (val.arrayValue.values || []).map(parseFirestoreValue);
  if ("mapValue" in val) {
    const obj: Record<string, any> = {};
    for (const [k, v] of Object.entries(val.mapValue.fields || {})) {
      obj[k] = parseFirestoreValue(v);
    }
    return obj;
  }
  return null;
}

// Helper to convert JS values to Firestore REST fields
function toFirestoreValue(val: any): any {
  if (val === null || val === undefined) return { nullValue: null };
  if (typeof val === "string") return { stringValue: val };
  if (typeof val === "boolean") return { booleanValue: val };
  if (typeof val === "number") {
    if (Number.isInteger(val)) return { integerValue: val.toString() };
    return { doubleValue: val };
  }
  if (Array.isArray(val)) {
    return { arrayValue: { values: val.map(toFirestoreValue) } };
  }
  if (typeof val === "object") {
    const fields: Record<string, any> = {};
    for (const [k, v] of Object.entries(val)) {
      fields[k] = toFirestoreValue(v);
    }
    return { mapValue: { fields } };
  }
  return { stringValue: String(val) };
}

async function getFirestoreDocument(
  projectId: string,
  databaseId: string,
  docPath: string,
  accessToken: string,
  transactionId?: string
): Promise<any | null> {
  let url = `${getFirestoreBaseUrl(projectId, databaseId)}/${docPath}`;
  if (transactionId) {
    url += `?transaction=${encodeURIComponent(transactionId)}`;
  }

  let res = await fetch(url, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });

  // Fallback to (default) if custom database returned 404
  if (!res.ok && res.status === 404 && databaseId !== "(default)" && !transactionId) {
    const fallbackUrl = `${getFirestoreBaseUrl(projectId, "(default)")}/${docPath}`;
    const fallbackRes = await fetch(fallbackUrl, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (fallbackRes.ok) {
      return await fallbackRes.json();
    }
  }

  if (res.status === 404) return null;
  if (!res.ok) {
    const errText = await res.text();
    throw new Error(`Firestore GET failed (${res.status}): ${errText}`);
  }

  return await res.json();
}

async function patchFirestoreDocument(
  projectId: string,
  databaseId: string,
  docPath: string,
  fields: Record<string, any>,
  updateMask: string[],
  accessToken: string
): Promise<void> {
  const maskParams = updateMask.map((m) => `updateMask.fieldPaths=${encodeURIComponent(m)}`).join("&");
  let url = `${getFirestoreBaseUrl(projectId, databaseId)}/${docPath}?${maskParams}`;

  const res = await fetch(url, {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ fields }),
  });

  if (!res.ok) {
    // If custom database returned 404, try default database
    if (res.status === 404 && databaseId !== "(default)") {
      const fallbackUrl = `${getFirestoreBaseUrl(projectId, "(default)")}/${docPath}?${maskParams}`;
      const fallbackRes = await fetch(fallbackUrl, {
        method: "PATCH",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ fields }),
      });
      if (fallbackRes.ok) return;
    }

    const errText = await res.text();
    throw new Error(`Firestore PATCH failed (${res.status}): ${errText}`);
  }
}

// Firestore Transactions for Concurrency-Safe Idempotency Locking
async function beginFirestoreTransaction(
  projectId: string,
  databaseId: string,
  accessToken: string
): Promise<string> {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${databaseId || "(default)"}/documents:beginTransaction`;
  const res = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ options: { readWrite: {} } }),
  });

  if (!res.ok) {
    const errText = await res.text();
    throw new Error(`Firestore beginTransaction failed (${res.status}): ${errText}`);
  }

  const data = await res.json();
  return data.transaction;
}

async function commitFirestoreTransaction(
  projectId: string,
  databaseId: string,
  transactionId: string,
  writes: any[],
  accessToken: string
): Promise<boolean> {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${databaseId || "(default)"}/documents:commit`;
  const res = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ transaction: transactionId, writes }),
  });

  if (res.status === 409) {
    // 409 Conflict: Another concurrent request committed first
    return false;
  }

  if (!res.ok) {
    const errText = await res.text();
    throw new Error(`Firestore commit failed (${res.status}): ${errText}`);
  }

  return true;
}

async function rollbackFirestoreTransaction(
  projectId: string,
  databaseId: string,
  transactionId: string,
  accessToken: string
): Promise<void> {
  try {
    const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${databaseId || "(default)"}/documents:rollback`;
    await fetch(url, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ transaction: transactionId }),
    });
  } catch (_e) {
    // Best-effort rollback
  }
}

// 4. Preference & Quiet Hours Checkers
function isConversationMutedForRecipient(
  userDocFields: any,
  conversationDocFields: any,
  conversationId: string,
  recipientUid: string
): boolean {
  if (!userDocFields) return false;

  // Global mute toggles
  if (userDocFields.notificationsMuted?.booleanValue === true) return true;
  if (userDocFields.notificationsEnabled?.booleanValue === false) return true;

  // Per-conversation mute on user profile
  const mutedConversations = (userDocFields.mutedConversations?.arrayValue?.values || [])
    .map((v: any) => v.stringValue)
    .filter(Boolean);
  if (mutedConversations.includes(conversationId)) return true;

  // Per-conversation mute on conversation doc
  const conversationMutedFor = (conversationDocFields.mutedFor?.arrayValue?.values || [])
    .map((v: any) => v.stringValue)
    .filter(Boolean);
  if (conversationMutedFor.includes(recipientUid)) return true;

  return false;
}

function isRecipientInQuietHours(userDocFields: any): boolean {
  if (!userDocFields || !userDocFields.quietHours) return false;

  const quietHours = parseFirestoreValue(userDocFields.quietHours);
  if (!quietHours || !quietHours.enabled) return false;

  const startHour = Number(quietHours.startHour);
  const endHour = Number(quietHours.endHour);
  if (isNaN(startHour) || isNaN(endHour) || startHour < 0 || startHour > 23 || endHour < 0 || endHour > 23) {
    return false;
  }

  const offsetMinutes = Number(quietHours.timezoneOffsetMinutes) || 0;
  const now = new Date();
  const utcMinutes = now.getUTCHours() * 60 + now.getUTCMinutes();
  const localMinutes = (utcMinutes + offsetMinutes + 1440) % 1440;
  const currentLocalHour = Math.floor(localMinutes / 60);

  if (startHour === endHour) {
    return true;
  } else if (startHour < endHour) {
    return currentLocalHour >= startHour && currentLocalHour < endHour;
  } else {
    // Spans midnight, e.g. 22:00 to 07:00
    return currentLocalHour >= startHour || currentLocalHour < endHour;
  }
}

// 5. FCM Push Delivery & Token Validation
function isConfirmedInvalidTokenError(errData: any, httpStatus: number): boolean {
  if (!errData?.error) {
    return httpStatus === 404;
  }
  const err = errData.error;
  const errorCode = err.details?.find((d: any) => d?.errorCode)?.errorCode;
  if (errorCode === "UNREGISTERED") return true;
  if (err.status === "NOT_FOUND" || httpStatus === 404) return true;

  // Strictly verify that INVALID_ARGUMENT is regarding the registration token itself
  if (errorCode === "INVALID_ARGUMENT" || err.status === "INVALID_ARGUMENT") {
    const msg = (err.message || "").toLowerCase();
    if (msg.includes("registration token") || msg.includes("token")) {
      return true;
    }
  }

  return false;
}

async function sendFcmNotification(
  projectId: string,
  token: string,
  conversationId: string,
  accessToken: string
): Promise<{ success: boolean; isInvalidToken: boolean }> {
  const url = `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`;
  const body = {
    message: {
      token: token,
      notification: {
        title: "New update",
        body: "You have a new update.",
      },
      data: {
        conversationId: conversationId,
      },
      android: {
        priority: "HIGH",
        notification: {
          channel_id: "private_workspace_messages",
          default_sound: true,
          default_vibrate_timings: true,
        },
      },
    },
  };

  const res = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });

  if (res.ok) {
    return { success: true, isInvalidToken: false };
  }

  const errData = await res.json().catch(() => null);
  const isInvalid = isConfirmedInvalidTokenError(errData, res.status);

  return { success: false, isInvalidToken: isInvalid };
}

// Main HTTP Request Handler
Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: CORS_HEADERS });
  }

  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed. Use POST." }), {
      status: 405,
      headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
    });
  }

  try {
    // 1. Parse and validate request body
    const body = await req.json().catch(() => null);
    if (!body || typeof body !== "object") {
      return new Response(JSON.stringify({ error: "Invalid request payload. Expected JSON." }), {
        status: 400,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    const { conversationId, messageId } = body;
    if (
      !conversationId ||
      !messageId ||
      typeof conversationId !== "string" ||
      typeof messageId !== "string" ||
      conversationId.trim().length === 0 ||
      messageId.trim().length === 0
    ) {
      return new Response(JSON.stringify({ error: "Missing or invalid conversationId or messageId." }), {
        status: 400,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    // 2. Extract Authorization Bearer header
    const authHeader = req.headers.get("Authorization") || "";
    if (!authHeader.startsWith("Bearer ")) {
      return new Response(JSON.stringify({ error: "Unauthorized: Missing Bearer token." }), {
        status: 401,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }
    const idToken = authHeader.substring(7).trim();

    // 3. Resolve Environment Credentials
    const rawServiceAccount = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON");
    let serviceAccount: any = null;
    if (rawServiceAccount) {
      try {
        serviceAccount = JSON.parse(rawServiceAccount);
      } catch {
        try {
          const decoded = new TextDecoder().decode(base64UrlDecode(rawServiceAccount));
          serviceAccount = JSON.parse(decoded);
        } catch (_e) {
          // ignore
        }
      }
    }

    const firebaseProjectId = Deno.env.get("FIREBASE_PROJECT_ID") ||
      serviceAccount?.project_id ||
      "gen-lang-client-0602929101";

    const databaseId = Deno.env.get("FIREBASE_DATABASE_ID") ||
      "ai-studio-android-privaten-9e43141e-cbed-4fc3-8277-72bc3d8cad51";

    if (!serviceAccount || !serviceAccount.client_email || !serviceAccount.private_key) {
      return new Response(
        JSON.stringify({ error: "Server configuration incomplete. Service account credentials missing." }),
        {
          status: 500,
          headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
        }
      );
    }

    // 4. Verify caller's Firebase ID Token
    let authenticatedUid: string;
    try {
      const verified = await verifyFirebaseIdToken(idToken, firebaseProjectId);
      authenticatedUid = verified.uid;
    } catch (_err) {
      return new Response(JSON.stringify({ error: "Unauthorized: Invalid or expired token." }), {
        status: 401,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    // 5. Mint Google OAuth2 token for Firestore & FCM HTTP v1
    const googleAccessToken = await getGoogleOAuth2Token(serviceAccount);

    // 6. Verify conversation exists and sender is an authorized participant
    const conversationDoc = await getFirestoreDocument(
      firebaseProjectId,
      databaseId,
      `conversations/${conversationId}`,
      googleAccessToken
    );

    if (!conversationDoc) {
      return new Response(JSON.stringify({ error: "Resource not found." }), {
        status: 404,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    const participantValues = conversationDoc.fields?.participantIds?.arrayValue?.values || [];
    const participantIds: string[] = participantValues.map((v: any) => v.stringValue).filter(Boolean);

    if (!participantIds.includes(authenticatedUid)) {
      return new Response(JSON.stringify({ error: "Access denied. Caller not in conversation." }), {
        status: 403,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    // 7. Atomic Concurrency-Safe Idempotency Check & Lock
    const transactionId = await beginFirestoreTransaction(firebaseProjectId, databaseId, googleAccessToken);
    let lockAcquired = false;

    let previousDeliveredTokens: string[] = [];
    let attemptCount = 0;

    try {
      const messageDoc = await getFirestoreDocument(
        firebaseProjectId,
        databaseId,
        `conversations/${conversationId}/messages/${messageId}`,
        googleAccessToken,
        transactionId
      );

      if (!messageDoc) {
        await rollbackFirestoreTransaction(firebaseProjectId, databaseId, transactionId, googleAccessToken);
        return new Response(JSON.stringify({ error: "Message not found in conversation." }), {
          status: 404,
          headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
        });
      }

      const senderId = messageDoc.fields?.senderId?.stringValue;
      if (senderId !== authenticatedUid) {
        await rollbackFirestoreTransaction(firebaseProjectId, databaseId, transactionId, googleAccessToken);
        return new Response(JSON.stringify({ error: "Access denied. Caller is not the message sender." }), {
          status: 403,
          headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
        });
      }

      // Inspect existing notificationState
      const existingNotificationState = messageDoc.fields?.notificationState
        ? parseFirestoreValue(messageDoc.fields.notificationState)
        : null;

      const now = Date.now();

      if (existingNotificationState) {
        // If already completely sent
        if (existingNotificationState.status === "sent") {
          await rollbackFirestoreTransaction(firebaseProjectId, databaseId, transactionId, googleAccessToken);
          return new Response(JSON.stringify({ success: true, status: "already_sent" }), {
            status: 200,
            headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
          });
        }

        // If currently in progress and lock has not expired
        const lockUntil = Number(existingNotificationState.lockUntil) || 0;
        if (existingNotificationState.status === "in_progress" && lockUntil > now) {
          await rollbackFirestoreTransaction(firebaseProjectId, databaseId, transactionId, googleAccessToken);
          return new Response(
            JSON.stringify({ success: true, status: "in_progress", message: "Notification is currently processing." }),
            {
              status: 200,
              headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
            }
          );
        }

        // If previously muted, no_recipients or no_tokens, no need to retry
        if (
          existingNotificationState.status === "muted" ||
          existingNotificationState.status === "no_recipients" ||
          existingNotificationState.status === "no_tokens"
        ) {
          await rollbackFirestoreTransaction(firebaseProjectId, databaseId, transactionId, googleAccessToken);
          return new Response(JSON.stringify({ success: true, status: existingNotificationState.status }), {
            status: 200,
            headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
          });
        }

        previousDeliveredTokens = Array.isArray(existingNotificationState.deliveredTokens)
          ? existingNotificationState.deliveredTokens
          : [];
        attemptCount = Number(existingNotificationState.attemptCount) || 0;
      }

      // Commit the in-progress concurrency lock
      const lockPayload = {
        status: "in_progress",
        lockUntil: now + 30000, // 30-second concurrency lock
        attemptCount: attemptCount + 1,
        lastAttemptAt: now,
        deliveredTokens: previousDeliveredTokens,
      };

      const docName = messageDoc.name;
      const writes = [
        {
          update: {
            name: docName,
            fields: {
              notificationState: toFirestoreValue(lockPayload),
            },
          },
          updateMask: {
            fieldPaths: ["notificationState"],
          },
        },
      ];

      lockAcquired = await commitFirestoreTransaction(
        firebaseProjectId,
        databaseId,
        transactionId,
        writes,
        googleAccessToken
      );
    } catch (err: any) {
      await rollbackFirestoreTransaction(firebaseProjectId, databaseId, transactionId, googleAccessToken);
      throw err;
    }

    if (!lockAcquired) {
      // Another concurrent worker acquired the lock first
      return new Response(
        JSON.stringify({ success: true, status: "in_progress", message: "Concurrent request is handling dispatch." }),
        {
          status: 200,
          headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
        }
      );
    }

    // 8. Determine intended recipients
    const recipientUids = participantIds.filter((id) => id !== authenticatedUid);
    if (recipientUids.length === 0) {
      await patchFirestoreDocument(
        firebaseProjectId,
        databaseId,
        `conversations/${conversationId}/messages/${messageId}`,
        {
          notificationState: toFirestoreValue({
            status: "no_recipients",
            lockUntil: 0,
            attemptCount: attemptCount + 1,
            lastAttemptAt: Date.now(),
          }),
        },
        ["notificationState"],
        googleAccessToken
      );

      return new Response(JSON.stringify({ success: true, status: "no_recipients" }), {
        status: 200,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    // 9. Process recipients, check preferences, and dispatch notifications
    let totalSent = 0;
    let totalFailed = 0;
    let allRecipientsMuted = true;
    let anyTokensFound = false;

    const successfullyDeliveredTokens = new Set<string>(previousDeliveredTokens);
    const tokensToRemoveByRecipient: Map<string, Set<string>> = new Map();

    for (const recipientUid of recipientUids) {
      const userDoc = await getFirestoreDocument(
        firebaseProjectId,
        databaseId,
        `users/${recipientUid}`,
        googleAccessToken
      );

      if (!userDoc || !userDoc.fields) continue;

      // Check per-conversation mute, global mute, and quiet hours
      const isMuted = isConversationMutedForRecipient(
        userDoc.fields,
        conversationDoc.fields,
        conversationId,
        recipientUid
      );
      const isQuiet = isRecipientInQuietHours(userDoc.fields);

      if (isMuted || isQuiet) {
        continue;
      }

      allRecipientsMuted = false;

      // Collect device tokens
      const recipientTokenSet = new Set<string>();
      if (userDoc.fields.fcmToken?.stringValue) {
        recipientTokenSet.add(userDoc.fields.fcmToken.stringValue);
      }
      const tokenArray = userDoc.fields.fcmTokens?.arrayValue?.values || [];
      for (const t of tokenArray) {
        if (t.stringValue) recipientTokenSet.add(t.stringValue);
      }

      if (recipientTokenSet.size > 0) {
        anyTokensFound = true;
      }

      // Dispatch to each device token
      for (const token of recipientTokenSet) {
        // Skip tokens that have already successfully received this notification
        if (successfullyDeliveredTokens.has(token)) {
          totalSent++;
          continue;
        }

        const result = await sendFcmNotification(
          firebaseProjectId,
          token,
          conversationId,
          googleAccessToken
        );

        if (result.success) {
          totalSent++;
          successfullyDeliveredTokens.add(token);
        } else {
          totalFailed++;
          if (result.isInvalidToken) {
            if (!tokensToRemoveByRecipient.has(recipientUid)) {
              tokensToRemoveByRecipient.set(recipientUid, new Set());
            }
            tokensToRemoveByRecipient.get(recipientUid)!.add(token);
          }
        }
      }
    }

    // 10. Clean up confirmed invalid device tokens
    for (const [uid, invalidTokens] of tokensToRemoveByRecipient.entries()) {
      try {
        const userDoc = await getFirestoreDocument(
          firebaseProjectId,
          databaseId,
          `users/${uid}`,
          googleAccessToken
        );

        if (userDoc?.fields) {
          const currentTokens = (userDoc.fields.fcmTokens?.arrayValue?.values || [])
            .map((v: any) => v.stringValue)
            .filter((t: string) => t && !invalidTokens.has(t));

          const singleToken = userDoc.fields.fcmToken?.stringValue;
          const updatedSingleToken = singleToken && !invalidTokens.has(singleToken)
            ? singleToken
            : currentTokens[0] || "";

          await patchFirestoreDocument(
            firebaseProjectId,
            databaseId,
            `users/${uid}`,
            {
              fcmTokens: {
                arrayValue: {
                  values: currentTokens.map((t: string) => ({ stringValue: t })),
                },
              },
              fcmToken: { stringValue: updatedSingleToken },
            },
            ["fcmTokens", "fcmToken"],
            googleAccessToken
          );
        }
      } catch (_e) {
        // Log clean-up warning without failing the main request
      }
    }

    // 11. Determine final outcome status
    let finalStatus: "sent" | "partial" | "failed" | "muted" | "no_tokens";

    if (allRecipientsMuted) {
      finalStatus = "muted";
    } else if (!anyTokensFound) {
      finalStatus = "no_tokens";
    } else if (totalSent > 0 && totalFailed === 0) {
      finalStatus = "sent";
    } else if (totalSent > 0 && totalFailed > 0) {
      finalStatus = "partial";
    } else {
      // All FCM attempts failed
      finalStatus = "failed";
    }

    // 12. Save final notificationState in Firestore
    const finalNotificationState = {
      status: finalStatus,
      lockUntil: 0, // Release lock
      attemptCount: attemptCount + 1,
      lastAttemptAt: Date.now(),
      finishedAt: Date.now(),
      sentCount: totalSent,
      failedCount: totalFailed,
      deliveredTokens: Array.from(successfullyDeliveredTokens),
    };

    const finalFields: Record<string, any> = {
      notificationState: toFirestoreValue(finalNotificationState),
    };
    const updateMaskFields = ["notificationState"];

    // Also update notificationSent boolean for backwards compatibility
    if (finalStatus === "sent" || finalStatus === "partial") {
      finalFields["notificationSent"] = { booleanValue: true };
      updateMaskFields.push("notificationSent");
    }

    await patchFirestoreDocument(
      firebaseProjectId,
      databaseId,
      `conversations/${conversationId}/messages/${messageId}`,
      finalFields,
      updateMaskFields,
      googleAccessToken
    );

    return new Response(
      JSON.stringify({
        success: finalStatus === "sent" || finalStatus === "partial",
        status: finalStatus,
        sentCount: totalSent,
        failedCount: totalFailed,
      }),
      {
        status: 200,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      }
    );
  } catch (_err: any) {
    // Sanitized client error response
    return new Response(
      JSON.stringify({ error: "An error occurred while processing the notification." }),
      {
        status: 500,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      }
    );
  }
});
