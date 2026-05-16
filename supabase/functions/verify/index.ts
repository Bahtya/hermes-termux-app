// Supabase Edge Function: verify
// 定期验证许可证状态，轮换验证令牌

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { sha256, randomToken } from "../_shared/crypto.ts";

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "METHOD_NOT_ALLOWED" }), {
      status: 405,
      headers: { "Content-Type": "application/json" },
    });
  }

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!
  );

  try {
    const body = await req.json();
    const { license_key, device_fingerprint, verification_token } = body;

    if (!license_key || !device_fingerprint || !verification_token) {
      return new Response(
        JSON.stringify({ valid: false, error: "MISSING_FIELDS" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    const keyHash = await sha256(license_key);

    // Lookup license
    const { data: license, error: licenseError } = await supabase
      .from("licenses")
      .select("id, status, plan")
      .eq("key_hash", keyHash)
      .single();

    if (licenseError || !license) {
      return new Response(
        JSON.stringify({ valid: false, error: "INVALID_KEY" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    if (license.status === "suspended") {
      return new Response(
        JSON.stringify({ valid: false, error: "KEY_SUSPENDED" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    // Lookup device
    const { data: device, error: deviceError } = await supabase
      .from("devices")
      .select("id, verification_token")
      .eq("license_id", license.id)
      .eq("device_fingerprint", device_fingerprint)
      .eq("is_active", true)
      .single();

    if (deviceError || !device) {
      return new Response(
        JSON.stringify({ valid: false, error: "DEVICE_MISMATCH" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    // Verify token
    if (device.verification_token !== verification_token) {
      return new Response(
        JSON.stringify({ valid: false, error: "TOKEN_MISMATCH" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    // Rotate token and update last_seen_at
    const newToken = randomToken();
    await supabase
      .from("devices")
      .update({
        verification_token: newToken,
        last_seen_at: new Date().toISOString(),
      })
      .eq("id", device.id);

    // Log successful verification (fire-and-forget)
    supabase.from("activation_events").insert({
      license_id: license.id,
      device_id: device.id,
      event_type: "verify",
      device_fingerprint,
      success: true,
    }).then(() => {});

    return new Response(
      JSON.stringify({
        valid: true,
        plan: license.plan,
        new_verification_token: newToken,
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  } catch (e) {
    return new Response(
      JSON.stringify({ valid: false, error: "INVALID_REQUEST" }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }
});
