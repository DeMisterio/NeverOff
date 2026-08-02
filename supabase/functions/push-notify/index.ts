import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const firebaseProjectId = Deno.env.get("FIREBASE_PROJECT_ID")
const firebaseClientEmail = Deno.env.get("FIREBASE_CLIENT_EMAIL")
const firebasePrivateKey = Deno.env.get("FIREBASE_PRIVATE_KEY")?.replace(/\\n/g, "\n")

async function getAccessToken(clientEmail: string, privateKey: string): Promise<string> {
  const cleanKey = privateKey
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s/g, "");
  const binaryKey = Uint8Array.from(atob(cleanKey), c => c.charCodeAt(0));
  
  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    binaryKey,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const header = { alg: "RS256", typ: "JWT" };
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    iss: clientEmail,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now
  };

  const textEncoder = new TextEncoder();
  const encodedHeader = btoa(JSON.stringify(header)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  const encodedPayload = btoa(JSON.stringify(payload)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  
  const stringToSign = `${encodedHeader}.${encodedPayload}`;
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    textEncoder.encode(stringToSign)
  );

  const encodedSignature = btoa(String.fromCharCode(...new Uint8Array(signature)))
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");

  const jwt = `${stringToSign}.${encodedSignature}`;

  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt
    })
  });

  const data = await response.json();
  if (data.error) {
    throw new Error(`OAuth error: ${data.error_description || data.error}`);
  }
  return data.access_token;
}

serve(async (req) => {
  try {
    const payload = await req.json()
    const record = payload.record // The new message inserted

    if (!record || !record.chat_id || !record.sender_id) {
        return new Response("Invalid payload", { status: 400 })
    }

    if (!firebaseProjectId || !firebaseClientEmail || !firebasePrivateKey) {
        return new Response("FCM config missing in environment secrets", { status: 500 })
    }

    // 1. Initialize Supabase client
    const supabaseClient = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    )

    // 2. Get the other participant in the chat
    const { data: participants, error: participantError } = await supabaseClient
      .from("chat_participants")
      .select("user_id")
      .eq("chat_id", record.chat_id)
      .neq("user_id", record.sender_id)

    if (participantError || !participants || participants.length === 0) {
      return new Response("Recipient not found", { status: 404 })
    }

    const recipientId = participants[0].user_id

    // 3. Get the recipient's FCM token and sender profile
    const { data: recipientProfile, error: profileError } = await supabaseClient
      .from("profiles")
      .select("fcm_token")
      .eq("id", recipientId)
      .single()

    if (profileError || !recipientProfile || !recipientProfile.fcm_token) {
      return new Response("Recipient FCM token not found", { status: 200 })
    }

    const { data: senderProfile } = await supabaseClient
      .from("profiles")
      .select("display_name")
      .eq("id", record.sender_id)
      .single()

    const senderName = senderProfile?.display_name || "NeverOff User"

    // 4. Authenticate with Google OAuth 2.0
    const accessToken = await getAccessToken(firebaseClientEmail, firebasePrivateKey)

    // 5. Send payload via Firebase FCM v1 HTTP API
    const fcmUrl = `https://fcm.googleapis.com/v1/projects/${firebaseProjectId}/messages:send`
    const fcmResponse = await fetch(fcmUrl, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        message: {
          token: recipientProfile.fcm_token,
          data: {
            id: record.id || "",
            chat_id: record.chat_id,
            sender_id: record.sender_id,
            sender_name: senderName,
            ciphertext: record.ciphertext,
            msg_key: record.msg_key,
            created_at: record.created_at || ""
          }
        }
      })
    })

    const fcmResult = await fcmResponse.json()

    return new Response(JSON.stringify({ success: true, result: fcmResult }), { 
      headers: { "Content-Type": "application/json" } 
    })

  } catch (error) {
    return new Response(JSON.stringify({ error: error.message }), { 
      headers: { "Content-Type": "application/json" }, 
      status: 500 
    })
  }
})
