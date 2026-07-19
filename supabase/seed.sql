-- ============================================================
-- PrisonConnect: Seed Data
-- ============================================================
-- This file is automatically loaded after migrations during
-- `supabase db reset` or `supabase db push`.
-- ============================================================

-- -----------------------------------------------------------
-- 1. SEED USERS
-- -----------------------------------------------------------
-- Test users with SHA-256 PIN hash for "123456"
-- Hash: 8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92
INSERT INTO public.users (prisoner_id, full_name, pin_hash, balance_remaining_seconds, account_status)
VALUES
    ('100001', 'John Doe',     '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92', 1200, 'active'),
    ('100002', 'Jane Smith',   '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92', 600,  'active'),
    ('100003', 'Bob Johnson',  '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92', 0,    'inactive')
ON CONFLICT (prisoner_id) DO NOTHING;

-- -----------------------------------------------------------
-- 2. SEED CONTACTS
-- -----------------------------------------------------------
-- John Doe's contacts
INSERT INTO public.contacts (contact_id, associated_inmate_id, full_name, phone_number, relationship_type)
SELECT 'CONTACT_001', id, 'Sarah Doe',         '+15551234567', 'FAMILY'
FROM public.users WHERE prisoner_id = '100001'
AND NOT EXISTS (SELECT 1 FROM public.contacts WHERE contact_id = 'CONTACT_001');

INSERT INTO public.contacts (contact_id, associated_inmate_id, full_name, phone_number, relationship_type)
SELECT 'CONTACT_002', id, 'Michael Doe',       '+15559876543', 'FAMILY'
FROM public.users WHERE prisoner_id = '100001'
AND NOT EXISTS (SELECT 1 FROM public.contacts WHERE contact_id = 'CONTACT_002');

INSERT INTO public.contacts (contact_id, associated_inmate_id, full_name, phone_number, relationship_type)
SELECT 'CONTACT_003', id, 'Attorney Jane',     '+15555550001', 'LAWYER'
FROM public.users WHERE prisoner_id = '100001'
AND NOT EXISTS (SELECT 1 FROM public.contacts WHERE contact_id = 'CONTACT_003');

INSERT INTO public.contacts (contact_id, associated_inmate_id, full_name, phone_number, relationship_type)
SELECT 'CONTACT_004', id, 'Facility Emergency','+15555550002', 'FACILITY_EMERGENCY'
FROM public.users WHERE prisoner_id = '100001'
AND NOT EXISTS (SELECT 1 FROM public.contacts WHERE contact_id = 'CONTACT_004');

-- Jane Smith's contacts
INSERT INTO public.contacts (contact_id, associated_inmate_id, full_name, phone_number, relationship_type)
SELECT 'CONTACT_005', id, 'Robert Smith',      '+15551112222', 'FAMILY'
FROM public.users WHERE prisoner_id = '100002'
AND NOT EXISTS (SELECT 1 FROM public.contacts WHERE contact_id = 'CONTACT_005');

INSERT INTO public.contacts (contact_id, associated_inmate_id, full_name, phone_number, relationship_type)
SELECT 'CONTACT_006', id, 'Legal Aid Clerk',   '+15553334444', 'LAWYER'
FROM public.users WHERE prisoner_id = '100002'
AND NOT EXISTS (SELECT 1 FROM public.contacts WHERE contact_id = 'CONTACT_006');