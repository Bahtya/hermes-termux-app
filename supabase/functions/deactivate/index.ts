// Supabase Edge Function: deactivate
// 解绑设备（释放设备槽位，需要 verification_token 验证身份）

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { sha256 } from "../_shared/crypto.ts";

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

    if (!license_key || !device_fingerprint) {
      return new Response(
        JSON.stringify({ success: false, error: "MISSING_FIELDS" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    const keyHash = await sha256(license_key);

    // Lookup license
    const { data: license, error: licenseError } = await supabase
      .from("licenses")
      .select("id")
      .eq("key_hash", keyHash)
      .single();

    if (licenseError || !license) {
      return new Response(
        JSON.stringify({ success: false, error: "INVALID_KEY" }),
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
        JSON.stringify({ success: false, error: "DEVICE_NOT_FOUND" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    // Verify token to confirm the requester is the legitimate device
    if (verification_token && device.verification_token !== verification_token) {
      return new Response(
        JSON.stringify({ success: false, error: "TOKEN_MISMATCH" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    // Deactivate device
    await supabase
      .from("devices")
      .update({ is_active: false })
      .eq("id", device.id);

    // Log
    supabase.from("activation_events").insert({
      license_id: license.id,
      device_id: device.id,
      event_type: "deactivate",
      device_fingerprint,
      success: true,
    }).then(() => {});

    return new Response(
      JSON.stringify({ success: true }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  } catch (e) {
    return new Response(
      JSON.stringify({ success: false, error: "INVALID_REQUEST" }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }
});
