// ============================================================
// PrisonConnect: Supabase Edge Function
// Activate a call room (replaces Firebase activateCallRoom)
// ============================================================
// Deploy with: supabase functions deploy activate-call-room
// ============================================================

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.0";

// CORS headers for the kiosk web app
const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

interface ActivateRequest {
  roomId?: string;
  room_id?: string;
}

serve(async (req: Request) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // Parse request body or query params
    let params: ActivateRequest;
    if (req.method === "GET") {
      const url = new URL(req.url);
      params = {
        roomId: url.searchParams.get("roomId") || undefined,
        room_id: url.searchParams.get("room_id") || undefined,
      };
    } else {
      params = await req.json();
    }

    const roomId = params.roomId || params.room_id;
    if (!roomId) {
      return new Response(
        JSON.stringify({ status: "ERROR", error: "missing_room_id" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Create Supabase client using environment variables
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const supabaseKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const supabase = createClient(supabaseUrl, supabaseKey);

    // Fetch the current room
    const { data: room, error: fetchError } = await supabase
      .from("call_rooms")
      .select("*")
      .eq("room_id", roomId)
      .single();

    if (fetchError || !room) {
      return new Response(
        JSON.stringify({ status: "ERROR", error: "room_not_found" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const currentStatus = room.room_status;
    if (!["WAITING", "OTP_SENT"].includes(currentStatus)) {
      return new Response(
        JSON.stringify({
          status: "ERROR",
          error: "invalid_room_state",
          currentStatus,
        }),
        { status: 409, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Activate the room
    const { error: updateError } = await supabase
      .from("call_rooms")
      .update({
        room_status: "ACTIVE",
        activated_at: new Date().toISOString(),
      })
      .eq("room_id", roomId);

    if (updateError) {
      throw updateError;
    }

    return new Response(
      JSON.stringify({ status: "SUCCESS", roomId, newStatus: "ACTIVE" }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    console.error("activateCallRoom error", error);
    return new Response(
      JSON.stringify({ status: "ERROR", error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});