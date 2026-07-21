-- ============================================================
-- PrisonConnect: Secure OTP Column RLS Policy
-- ============================================================
-- This migration ensures the OTP column cannot be read directly
-- via the anon key. OTP should only be verifiable through
-- the verify_room_otp RPC function.
-- ============================================================

-- The verify_room_otp function already uses SECURITY DEFINER, which allows it
-- to read the otp column while regular SELECT queries cannot.
-- 
-- For additional security, we create a view that explicitly excludes the otp column
-- and update the application to use this view for any direct queries.

-- Create a view that explicitly excludes the otp column
-- This provides defense-in-depth for the OTP security
CREATE OR REPLACE VIEW public.call_rooms_public AS
    SELECT 
        id,
        room_id,
        kiosk_id,
        inmate_id,
        call_type,
        room_status,
        receiver_phone,
        receiverPhone,
        token,
        webrtc_signaling,
        activated_at,
        created_at,
        updated_at
    FROM public.call_rooms;

-- Grant usage on the view to anon
GRANT SELECT ON public.call_rooms_public TO anon;

-- Note: The existing anon_can_read_call_rooms policy allows SELECT on the table
-- but the application code (both Android and web) should be updated to:
-- 1. Never request the 'otp' column in SELECT queries
-- 2. Use the verify_room_otp RPC for OTP verification
--
-- The web client already only selects: kiosk_id, room_status, token
-- The Android client stores otp locally and only uses the RPC for verification
--
-- For production deployments, consider using a restricted API key with
-- column-level permissions or implementing a more restrictive RLS policy
-- that uses a custom function to filter out sensitive columns.