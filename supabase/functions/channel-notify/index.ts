// Fan-out push for a new channel post.
//
// Unlike push-notify (1:1, one recipient, encrypted body), a channel post goes
// to every subscriber who hasn't muted the channel, and its body is plaintext —
// channel posts are broadcast, so there is nothing to decrypt client-side.
//
// FCM v1 has no multicast endpoint, so tokens are sent in bounded parallel
// batches rather than one request per subscriber in series.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const firebaseProjectId = Deno.env.get("FIREBASE_PROJECT_ID")
const firebaseClientEmail = Deno.env.get("FIREBASE_CLIENT_EMAIL")
const firebasePrivateKey = Deno.env.get("FIREBASE_PRIVATE_KEY")?.replace(/\\n/g, "\n")

/** Subscribers pushed concurrently per wave. */
const BATCH_SIZE = 20

async function getAccessToken(clientEmail: string, privateKey: string): Promise<string> {
  const cleanKey = privateKey
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s/g, "")
  const binaryKey = Uint8Array.from(atob(cleanKey), c => c.charCodeAt(0))

  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    binaryKey,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  )

  const header = { alg: "RS256", typ: "JWT" }
  const now = Math.floor(Date.now() / 1000)
  const payload = {
    iss: clientEmail,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now
  }

  const textEncoder = new TextEncoder()
  const encodedHeader = btoa(JSON.stringify(header)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_")
  const encodedPayload = btoa(JSON.stringify(payload)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_")

  const stringToSign = `${encodedHeader}.${encodedPayload}`
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    textEncoder.encode(stringToSign)
  )

  const encodedSignature = btoa(String.fromCharCode(...new Uint8Array(signature)))
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")

  const jwt = `${stringToSign}.${encodedSignature}`

  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt
    })
  })

  const data = await response.json()
  if (data.error) {
    throw new Error(`OAuth error: ${data.error_description || data.error}`)
  }
  return data.access_token
}

/** Notification bodies are a preview, not the post — keep them short. */
function preview(content: string): string {
  if (content.startsWith("[IMAGE]")) return "📷 Photo"
  if (content.startsWith("[VIDEO]")) return "🎬 Video"
  if (content.startsWith("[VOICE]")) return "🎤 Voice message"
  return content.length > 140 ? `${content.slice(0, 140)}…` : content
}

serve(async (req) => {
  try {
    const payload = await req.json()
    const record = payload.record

    if (!record || !record.channel_id || !record.author_id) {
      return new Response("Invalid payload", { status: 400 })
    }

    if (!firebaseProjectId || !firebaseClientEmail || !firebasePrivateKey) {
      return new Response("FCM config missing in environment secrets", { status: 500 })
    }

    const supabaseClient = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    )

    const { data: channel, error: channelError } = await supabaseClient
      .from("channels")
      .select("name, owner_id")
      .eq("id", record.channel_id)
      .single()

    if (channelError || !channel) {
      return new Response("Channel not found", { status: 404 })
    }

    // Only the owner may post, so a request claiming otherwise is not ours to act on.
    if (channel.owner_id !== record.author_id) {
      return new Response("Author is not the channel owner", { status: 403 })
    }

    const { data: subscribers } = await supabaseClient
      .from("channel_subscribers")
      .select("user_id")
      .eq("channel_id", record.channel_id)
      .eq("notifications_enabled", true)
      .neq("user_id", record.author_id)

    if (!subscribers || subscribers.length === 0) {
      return new Response(JSON.stringify({ success: true, sent: 0 }), {
        headers: { "Content-Type": "application/json" }
      })
    }

    const { data: profiles } = await supabaseClient
      .from("profiles")
      .select("fcm_token")
      .in("id", subscribers.map((s: { user_id: string }) => s.user_id))

    const tokens = (profiles ?? [])
      .map((p: { fcm_token: string | null }) => p.fcm_token)
      .filter((t: string | null): t is string => !!t)

    if (tokens.length === 0) {
      return new Response(JSON.stringify({ success: true, sent: 0 }), {
        headers: { "Content-Type": "application/json" }
      })
    }

    const accessToken = await getAccessToken(firebaseClientEmail, firebasePrivateKey)
    const fcmUrl = `https://fcm.googleapis.com/v1/projects/${firebaseProjectId}/messages:send`

    const body = preview(record.content ?? "")
    let sent = 0
    let failed = 0

    for (let i = 0; i < tokens.length; i += BATCH_SIZE) {
      const batch = tokens.slice(i, i + BATCH_SIZE)
      const results = await Promise.allSettled(
        batch.map((token) =>
          fetch(fcmUrl, {
            method: "POST",
            headers: {
              "Authorization": `Bearer ${accessToken}`,
              "Content-Type": "application/json"
            },
            body: JSON.stringify({
              message: {
                token,
                data: {
                  type: "channel_post",
                  id: record.id ?? "",
                  channel_id: record.channel_id,
                  channel_name: channel.name ?? "Channel",
                  author_id: record.author_id,
                  content: record.content ?? "",
                  preview: body,
                  created_at: record.created_at ?? ""
                }
              }
            })
          })
        )
      )
      for (const result of results) {
        if (result.status === "fulfilled" && result.value.ok) sent++
        else failed++
      }
    }

    return new Response(JSON.stringify({ success: true, sent, failed }), {
      headers: { "Content-Type": "application/json" }
    })
  } catch (error) {
    return new Response(JSON.stringify({ error: (error as Error).message }), {
      headers: { "Content-Type": "application/json" },
      status: 500
    })
  }
})
