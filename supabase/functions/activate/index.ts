// Supabase Edge Function: activate
// 激活许可证密钥并绑定设备

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { sha256, randomToken } from "../_shared/crypto.ts";

const RATE_LIMIT_MAX = 5;
const RATE_LIMIT_WINDOW_MS = 60_000;
const rateLimiter = new Map<string, { count: number; resetAt: number }>();

function checkRateLimit(ip: string): boolean {
  const now = Date.now();
  const entry = rateLimiter.get(ip);
  if (!entry || now > entry.resetAt) {
    rateLimiter.set(ip, { count: 1, resetAt: now + RATE_LIMIT_WINDOW_MS });
    return true;
  }
  if (entry.count >= RATE_LIMIT_MAX) return false;
  entry.count++;
  return true;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "METHOD_NOT_ALLOWED" }), {
      status: 405,
      headers: { "Content-Type": "application/json" },
    });
  }

  // Rate limit
  const clientIp =
    req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
  if (!checkRateLimit(clientIp)) {
    return new Response(JSON.stringify({ error: "RATE_LIMITED" }), {
      status: 429,
      headers: { "Content-Type": "application/json" },
    });
  }

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!
  );

  try {
    const body = await req.json();
    const {
      license_key,
      device_fingerprint,
      device_name,
      android_id,
      app_version,
    } = body;

    if (!license_key || !device_fingerprint) {
      return new Response(
        JSON.stringify({ success: false, error: "MISSING_FIELDS" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    const keyHash = await sha256(license_key);

    // Lookup license by key_hash
    const { data: license, error: licenseError } = await supabase
      .from("licenses")
      .select("id, status, max_devices, plan")
      .eq("key_hash", keyHash)
      .single();

    if (licenseError || !license) {
      logEvent(supabase, null, null, "activate", device_fingerprint, false, "INVALID_KEY");
      return new Response(
        JSON.stringify({ success: false, error: "INVALID_KEY" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    if (license.status === "suspended") {
      logEvent(supabase, license.id, null, "activate", device_fingerprint, false, "KEY_SUSPENDED");
      return new Response(
        JSON.stringify({ success: false, error: "KEY_SUSPENDED" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    // Check if device already bound
    const { data: existingDevice } = await supabase
      .from("devices")
      .select("id, verification_token")
      .eq("license_id", license.id)
      .eq("device_fingerprint", device_fingerprint)
      .eq("is_active", true)
      .single();

    if (existingDevice) {
      // Idempotent: update last_seen_at
      const token = existingDevice.verification_token || randomToken();
      await supabase
        .from("devices")
        .update({ last_seen_at: new Date().toISOString(), verification_token: token })
        .eq("id", existingDevice.id);

      logEvent(supabase, license.id, existingDevice.id, "activate", device_fingerprint, true, null);

      return new Response(
        JSON.stringify({
          success: true,
          license_id: license.id,
          device_id: existingDevice.id,
          plan: license.plan,
          verification_token: token,
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    // Count active devices
    const { count } = await supabase
      .from("devices")
      .select("id", { count: "exact", head: true })
      .eq("license_id", license.id)
      .eq("is_active", true);

    if ((count || 0) >= license.max_devices) {
      logEvent(supabase, license.id, null, "activate", device_fingerprint, false, "MAX_DEVICES_REACHED");
      return new Response(
        JSON.stringify({ success: false, error: "MAX_DEVICES_REACHED" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    // Insert new device
    const token = randomToken();
    const { data: newDevice, error: insertError } = await supabase
      .from("devices")
      .insert({
        license_id: license.id,
        device_fingerprint,
        device_name: device_name || null,
        android_id: android_id || null,
        app_version: app_version || null,
        verification_token: token,
      })
      .select("id")
      .single();

    if (insertError) {
      return new Response(
        JSON.stringify({ success: false, error: "INTERNAL_ERROR" }),
        { status: 500, headers: { "Content-Type": "application/json" } }
      );
    }

    // Set license status to active if was inactive
    if (license.status === "inactive") {
      await supabase
        .from("licenses")
        .update({ status: "active" })
        .eq("id", license.id);
    }

    logEvent(supabase, license.id, newDevice.id, "activate", device_fingerprint, true, null);

    return new Response(
      JSON.stringify({
        success: true,
        license_id: license.id,
        device_id: newDevice.id,
        plan: license.plan,
        verification_token: token,
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  } catch (e) {
    return new Response(
      JSON.stringify({ success: false, error: "INVALID_REQUEST" }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }
});

// Fire-and-forget event logging — does not block the response
function logEvent(
  supabase: ReturnType<typeof createClient>,
  licenseId: string | null,
  deviceId: string | null,
  eventType: string,
  fingerprint: string,
  success: boolean,
  errorMessage: string | null
) {
  supabase.from("activation_events").insert({
    license_id: licenseId,
    device_id: deviceId,
    event_type: eventType,
    device_fingerprint: fingerprint,
    success,
    error_message: errorMessage,
  }).then(() => {});
}
