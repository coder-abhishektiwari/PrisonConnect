-- ============================================================
-- PrisonConnect: Secure Call Sync & Realtime Signaling
-- ============================================================

-- 1. Add OTP and Token columns to call_rooms
ALTER TABLE public.call_rooms
ADD COLUMN IF NOT EXISTS otp TEXT,
ADD COLUMN IF NOT EXISTS token TEXT;

-- 2. Create the RPC function to verify OTP
CREATE OR REPLACE FUNCTION verify_room_otp(target_room TEXT, input_otp TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    is_valid BOOLEAN;
BEGIN
    -- Check if OTP matches for the given room
    SELECT (otp = input_otp) INTO is_valid
    FROM public.call_rooms
    WHERE room_id = target_room AND room_status IN ('WAITING', 'OTP_SENT');

    IF is_valid THEN
        -- Update room to ACTIVE on successful verification
        UPDATE public.call_rooms
        SET room_status = 'ACTIVE',
            activated_at = now()
        WHERE room_id = target_room;
        RETURN TRUE;
    ELSE
        RETURN FALSE;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 3. Grant access to the function
GRANT EXECUTE ON FUNCTION verify_room_otp(TEXT, TEXT) TO anon;
GRANT EXECUTE ON FUNCTION verify_room_otp(TEXT, TEXT) TO authenticated;
