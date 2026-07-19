-- ============================================================
-- PrisonConnect: Initial Schema Migration
-- ============================================================
-- This migration creates the database schema for the
-- PrisonConnect inmate communication kiosk application.
-- ============================================================

-- -----------------------------------------------------------
-- 1. USERS TABLE
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prisoner_id   TEXT UNIQUE NOT NULL,
    full_name     TEXT NOT NULL DEFAULT '',
    pin_hash      TEXT NOT NULL DEFAULT '',
    balance_remaining_seconds BIGINT NOT NULL DEFAULT 0,
    account_status TEXT NOT NULL DEFAULT 'active' CHECK (account_status IN ('active', 'inactive', 'suspended')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_users_prisoner_id ON public.users (prisoner_id);
CREATE INDEX IF NOT EXISTS idx_users_pin_hash ON public.users (pin_hash);

-- -----------------------------------------------------------
-- 2. CONTACTS TABLE
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.contacts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id          TEXT NOT NULL DEFAULT '',
    associated_inmate_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    full_name           TEXT NOT NULL DEFAULT '',
    phone_number        TEXT NOT NULL DEFAULT '',
    relationship_type   TEXT NOT NULL DEFAULT '' CHECK (relationship_type IN ('FAMILY', 'LAWYER', 'FACILITY_EMERGENCY', '')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_contacts_inmate ON public.contacts (associated_inmate_id);

-- -----------------------------------------------------------
-- 3. CALL_ROOMS TABLE
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.call_rooms (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id         TEXT UNIQUE NOT NULL,
    kiosk_id        TEXT NOT NULL DEFAULT 'KIOSK_001',
    inmate_id       TEXT NOT NULL DEFAULT '',
    call_type       TEXT NOT NULL DEFAULT 'VIDEO' CHECK (call_type IN ('AUDIO', 'VIDEO')),
    room_status     TEXT NOT NULL DEFAULT 'WAITING' CHECK (room_status IN ('WAITING', 'OTP_SENT', 'ACTIVE', 'CONNECTED', 'DISCONNECTED', 'TAMPER_KILLED')),
    receiver_phone  TEXT,
    receiverPhone   TEXT,
    webrtc_signaling JSONB NOT NULL DEFAULT '{"offer":null,"answer":null,"iceCandidates":[]}',
    activated_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_call_rooms_room_id ON public.call_rooms (room_id);

-- -----------------------------------------------------------
-- 4. UPDATED_AT TRIGGER
-- -----------------------------------------------------------
CREATE OR REPLACE FUNCTION public.update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER set_users_updated_at
    BEFORE UPDATE ON public.users
    FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_contacts_updated_at
    BEFORE UPDATE ON public.contacts
    FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

CREATE TRIGGER set_call_rooms_updated_at
    BEFORE UPDATE ON public.call_rooms
    FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

-- -----------------------------------------------------------
-- 5. ROW LEVEL SECURITY
-- -----------------------------------------------------------
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.call_rooms ENABLE ROW LEVEL SECURITY;

-- Allow anon key to read users (for login verification)
CREATE POLICY "anon_can_read_users" ON public.users
    FOR SELECT USING (true);

-- Allow anon key to read contacts
CREATE POLICY "anon_can_read_contacts" ON public.contacts
    FOR SELECT USING (true);

-- Allow anon key to read/write call_rooms (for WebRTC signaling)
CREATE POLICY "anon_can_read_call_rooms" ON public.call_rooms
    FOR SELECT USING (true);

CREATE POLICY "anon_can_insert_call_rooms" ON public.call_rooms
    FOR INSERT WITH CHECK (true);

CREATE POLICY "anon_can_update_call_rooms" ON public.call_rooms
    FOR UPDATE USING (true);

-- -----------------------------------------------------------
-- 6. STORAGE BUCKET for recordings
-- -----------------------------------------------------------
INSERT INTO storage.buckets (id, name, public)
VALUES ('recordings', 'recordings', false)
ON CONFLICT (id) DO NOTHING;

-- Allow anon to upload to recordings bucket
CREATE POLICY "anon_can_upload_recordings" ON storage.objects
    FOR INSERT WITH CHECK (bucket_id = 'recordings');

-- Allow anon to read their own recordings
CREATE POLICY "anon_can_read_recordings" ON storage.objects
    FOR SELECT USING (bucket_id = 'recordings');