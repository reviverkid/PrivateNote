// Supabase Edge Function: send-chat-notification
// Exclusively acts as a server-side sender for Firebase Cloud Messaging (FCM) push notifications.
// Primary database remains Cloud Firestore; no user or chat data is persisted in Supabase.

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

// Cached Google public keys and OAuth2 access token
let cachedJwks: { keys: any[]; fetchedAt: number } | null = null;
let cachedGoogleToken: { token: string; expiresAt: number } | null = null;

// Helper: Base64URL decode
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

// Helper: Base64URL encode
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

// Helper: String to Base64URL
function stringToBase64Url(str: string): string {
  return base64UrlEncode(new TextEncoder().encode(str));
}

// 1. Verify Firebase ID Token
async function verifyFirebaseIdToken(
  token: string,
  expectedProjectId: string
): Promise<{ uid: string; email?: string }> {
  const parts = token.split(".");
  if (parts.length !== 3) {
    throw new Error("Invalid JWT format");
  }

  const [headerB64, payloadB64, signatureB64] = parts;
  const header = JSON.parse(new TextDecoder().decode(base64UrlDecode(headerB64)));
  const payload = JSON.parse(new TextDecoder().decode(base64UrlDecode(payloadB64)));

  // Validate timing
  const now = Math.floor(Date.now() / 1000);
  if (payload.exp && payload.exp < now) {
    throw new Error("Firebase ID token has expired");
  }
  if (payload.iat && payload.iat > now + 300) {
    throw new Error("Token issued in the future");
  }

  // Validate issuer and audience
  if (payload.iss !== `https://securetoken.google.com/${expectedProjectId}`) {
    throw new Error(`Token issuer mismatch: expected https://securetoken.google.com/${expectedProjectId}, got ${payload.iss}`);
  }
  if (payload.aud !== expectedProjectId) {
    throw new Error(`Token audience mismatch: expected ${expectedProjectId}, got ${payload.aud}`);
  }

  // Fetch Google JWKS
  if (!cachedJwks || Date.now() - cachedJwks.fetchedAt > 3600000) {
    const res = await fetch("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com");
    if (!res.ok) {
      throw new Error("Failed to fetch Google public keys for Firebase token verification");
    }
    const data = await res.json();
    cachedJwks = { keys: data.keys, fetchedAt: Date.now() };
  }

  const keyDef = cachedJwks.keys.find((k: any) => k.kid === header.kid);
  if (!keyDef) {
    throw new Error(`Public key matching kid '${header.kid}' not found`);
  }

  const cryptoKey = await crypto.subtle.importKey(
    "jwk",
    keyDef,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"]
  );

  const signedData = new TextEncoder().encode(`${headerB64}.${payloadB64}`);
  const signature = base64UrlDecode(signatureB64);

  const isValid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    signature,
    signedData
  );

  if (!isValid) {
    throw new Error("Invalid token signature");
  }

  const uid = payload.user_id || payload.sub;
  if (!uid) {
    throw new Error("No user id present in token payload");
  }

  return { uid, email: payload.email };
}

// 2. Parse PEM private key for Google OAuth2
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

// 3. Mint Google OAuth2 Token from Service Account
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
    const errorText = await tokenRes.text();
    throw new Error(`Google OAuth2 token exchange failed: ${tokenRes.status} ${errorText}`);
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

// 4. Firestore REST helpers
function getFirestoreUrl(projectId: string, databaseId: string, docPath: string): string {
  const db = databaseId || "(default)";
  return `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${db}/documents/${docPath}`;
}

async function getFirestoreDocument(
  projectId: string,
  databaseId: string,
  docPath: string,
  accessToken: string
): Promise<any | null> {
  // First try the configured databaseId
  let url = getFirestoreUrl(projectId, databaseId, docPath);
  let res = await fetch(url, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });

  // If not found and database was not '(default)', try fallback to '(default)'
  if (!res.ok && res.status === 404 && databaseId !== "(default)") {
    const fallbackUrl = getFirestoreUrl(projectId, "(default)", docPath);
    const fallbackRes = await fetch(fallbackUrl, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (fallbackRes.ok) {
      return await fallbackRes.json();
    }
  }

  if (res.status === 404) return null;
  if (!res.ok) {
    const err = await res.text();
    throw new Error(`Firestore GET ${docPath} failed: ${res.status} ${err}`);
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
  const url = `${getFirestoreUrl(projectId, databaseId, docPath)}?${maskParams}`;

  const res = await fetch(url, {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ fields }),
  });

  if (!res.ok && res.status === 404 && databaseId !== "(default)") {
    const fallbackUrl = `${getFirestoreUrl(projectId, "(default)", docPath)}?${maskParams}`;
    await fetch(fallbackUrl, {
      method: "PATCH",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ fields }),
    });
  }
}

// 5. Send FCM HTTP v1 Notification
async function sendFcmNotification(
  projectId: string,
  token: string,
  conversationId: string,
  accessToken: string
): Promise<{ success: boolean; errorStatus?: string }> {
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
    return { success: true };
  }

  const errData = await res.json().catch(() => null);
  const errorStatus = errData?.error?.details?.find((d: any) => d?.errorCode)?.errorCode ||
    errData?.error?.status ||
    `HTTP_${res.status}`;

  return { success: false, errorStatus };
}

// Main Handler
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
    // Read and parse request body
    const body = await req.json().catch(() => null);
    if (!body || typeof body !== "object") {
      return new Response(JSON.stringify({ error: "Invalid request payload. Expected JSON." }), {
        status: 400,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    const { conversationId, messageId } = body;
    if (!conversationId || !messageId || typeof conversationId !== "string" || typeof messageId !== "string") {
      return new Response(JSON.stringify({ error: "Missing conversationId or messageId in request body." }), {
        status: 400,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    // Extract Bearer token
    const authHeader = req.headers.get("Authorization") || "";
    if (!authHeader.startsWith("Bearer ")) {
      return new Response(JSON.stringify({ error: "Missing or invalid Authorization header. Expected Bearer <Firebase_ID_Token>." }), {
        status: 401,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }
    const idToken = authHeader.substring(7).trim();

    // Configuration from Environment / Secrets
    const rawServiceAccount = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON");
    let serviceAccount: any = null;
    if (rawServiceAccount) {
      try {
        serviceAccount = JSON.parse(rawServiceAccount);
      } catch {
        // If passed as base64
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
        JSON.stringify({
          error: "FIREBASE_SERVICE_ACCOUNT_JSON secret is not set in Supabase. Please configure it via `supabase secrets set FIREBASE_SERVICE_ACCOUNT_JSON=...`.",
        }),
        {
          status: 500,
          headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
        }
      );
    }

    // 1. Verify caller's Firebase ID token
    let authenticatedUid: string;
    try {
      const verified = await verifyFirebaseIdToken(idToken, firebaseProjectId);
      authenticatedUid = verified.uid;
    } catch (err: any) {
      return new Response(JSON.stringify({ error: `Authentication failed: ${err.message}` }), {
        status: 401,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    // 2. Obtain Google OAuth2 access token for Firestore and FCM HTTP v1
    const googleAccessToken = await getGoogleOAuth2Token(serviceAccount);

    // 3. Verify conversation exists and sender is a participant
    const conversationDoc = await getFirestoreDocument(
      firebaseProjectId,
      databaseId,
      `conversations/${conversationId}`,
      googleAccessToken
    );

    if (!conversationDoc) {
      return new Response(JSON.stringify({ error: "Conversation not found." }), {
        status: 404,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    const participantValues = conversationDoc.fields?.participantIds?.arrayValue?.values || [];
    const participantIds: string[] = participantValues.map((v: any) => v.stringValue).filter(Boolean);

    if (!participantIds.includes(authenticatedUid)) {
      return new Response(JSON.stringify({ error: "Access denied. Caller is not a participant in this conversation." }), {
        status: 403,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    // 4. Verify message exists, was sent by authenticatedUid, and has not yet generated a notification (idempotency)
    const messageDoc = await getFirestoreDocument(
      firebaseProjectId,
      databaseId,
      `conversations/${conversationId}/messages/${messageId}`,
      googleAccessToken
    );

    if (!messageDoc) {
      return new Response(JSON.stringify({ error: "Message not found in conversation." }), {
        status: 404,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    const senderId = messageDoc.fields?.senderId?.stringValue;
    if (senderId !== authenticatedUid) {
      return new Response(JSON.stringify({ error: "Message sender does not match authenticated user." }), {
        status: 403,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      });
    }

    // Check if notification already sent
    const alreadySent = messageDoc.fields?.notificationSent?.booleanValue;
    if (alreadySent === true) {
      return new Response(
        JSON.stringify({
          status: "already_sent",
          message: "Notification already sent for this message.",
        }),
        {
          status: 200,
          headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
        }
      );
    }

    // 5. Determine intended recipient(s)
    const recipientUids = participantIds.filter((id) => id !== authenticatedUid);
    if (recipientUids.length === 0) {
      return new Response(
        JSON.stringify({
          status: "no_recipients",
          message: "No other participants in conversation.",
        }),
        {
          status: 200,
          headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
        }
      );
    }

    // 6. Retrieve registered FCM device tokens for each recipient
    let sentCount = 0;
    let failedCount = 0;
    const tokensToRemove: { uid: string; token: string }[] = [];

    for (const recipientUid of recipientUids) {
      const userDoc = await getFirestoreDocument(
        firebaseProjectId,
        databaseId,
        `users/${recipientUid}`,
        googleAccessToken
      );

      if (!userDoc || !userDoc.fields) continue;

      // Check per-chat mute or global mute preferences
      const mutedConversations = userDoc.fields?.mutedConversations?.arrayValue?.values?.map(
        (v: any) => v.stringValue
      ) || [];
      const globalMute = userDoc.fields?.notificationsMuted?.booleanValue === true;

      if (globalMute || mutedConversations.includes(conversationId)) {
        continue;
      }

      // Collect FCM tokens
      const tokenSet = new Set<string>();
      if (userDoc.fields.fcmToken?.stringValue) {
        tokenSet.add(userDoc.fields.fcmToken.stringValue);
      }
      const fcmTokensArray = userDoc.fields.fcmTokens?.arrayValue?.values || [];
      for (const val of fcmTokensArray) {
        if (val.stringValue) tokenSet.add(val.stringValue);
      }

      // Send push notification to each registered device token
      for (const token of tokenSet) {
        const result = await sendFcmNotification(
          firebaseProjectId,
          token,
          conversationId,
          googleAccessToken
        );

        if (result.success) {
          sentCount++;
        } else {
          failedCount++;
          // If token is invalid or unregistered, schedule removal
          if (
            result.errorStatus === "UNREGISTERED" ||
            result.errorStatus === "INVALID_ARGUMENT" ||
            result.errorStatus === "NOT_FOUND" ||
            result.errorStatus === "HTTP_404"
          ) {
            tokensToRemove.push({ uid: recipientUid, token });
          }
        }
      }
    }

    // 7. Cleanup stale tokens if any
    for (const item of tokensToRemove) {
      try {
        // Remove token from recipient user record
        const userDoc = await getFirestoreDocument(
          firebaseProjectId,
          databaseId,
          `users/${item.uid}`,
          googleAccessToken
        );
        if (userDoc?.fields?.fcmTokens?.arrayValue?.values) {
          const currentList = userDoc.fields.fcmTokens.arrayValue.values
            .map((v: any) => v.stringValue)
            .filter((t: string) => t && t !== item.token);

          await patchFirestoreDocument(
            firebaseProjectId,
            databaseId,
            `users/${item.uid}`,
            {
              fcmTokens: {
                arrayValue: {
                  values: currentList.map((t: string) => ({ stringValue: t })),
                },
              },
            },
            ["fcmTokens"],
            googleAccessToken
          );
        }
      } catch (_e) {
        // Non-blocking cleanup
      }
    }

    // 8. Mark notificationSent = true on message in Firestore
    try {
      await patchFirestoreDocument(
        firebaseProjectId,
        databaseId,
        `conversations/${conversationId}/messages/${messageId}`,
        {
          notificationSent: { booleanValue: true },
        },
        ["notificationSent"],
        googleAccessToken
      );
    } catch (_e) {
      // Non-blocking
    }

    return new Response(
      JSON.stringify({
        success: true,
        conversationId,
        messageId,
        sentCount,
        failedCount,
      }),
      {
        status: 200,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      }
    );
  } catch (err: any) {
    return new Response(
      JSON.stringify({
        error: "Internal server error while processing notification",
        details: err.message,
      }),
      {
        status: 500,
        headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      }
    );
  }
});
