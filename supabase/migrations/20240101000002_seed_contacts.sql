-- ============================================================
-- PrisonConnect: Seed Contacts for Test Users
-- ============================================================
-- Run this AFTER the initial schema migration.
-- This inserts sample contacts linked to the users seeded above.
-- ============================================================

-- Insert contacts for John Doe (prisoner_id = '100001')
-- We need to look up the actual UUID from the users table
INSERT INTO public.contacts (contact_id, associated_inmate_id, full_name, phone_number, relationship_type)
SELECT
    'CONTACT_001',
    id,
    'Sarah Doe',
    '+15551234567',
    'FAMILY'
FROM public.users
WHERE prisoner_id = '100001'
AND NOT EXISTS (SELECT 1 FROM public.contacts WHERE contact_id = 'CONTACT_001');

INSERT INTO public.contacts (contact_id, associated_inmate_id, full_name, phone_number, relationship_type)
SELECT
    'CONTACT_002',
    id,
    'Michael Doe',
    '+15559876543',
    'FAMILY'
FROM public.users
WHERE prisoner_id = '100001'
AND NOT EXISTS (SELECT 1 FROM public.contacts WHERE contact_id = 'CONTACT_002');

INSERT INTO public.contacts (contact_id, associated_inmate_id, full_name, phone_number, relationship_type)
SELECT
    'CONTACT_003',
    id,
    'Attorney Jane',
    '+15555550001',
    'LAWYER'
FROM public.users
WHERE prisoner_id = '100001'
AND NOT EXISTS (SELECT 1 FROM public.contacts WHERE contact_id = 'CONTACT_003');

INSERT INTO public.contacts (contact_id, associated_inmate_id, full_name, phone_number, relationship_type)
SELECT
    'CONTACT_004',
    id,
    'Facility Emergency',
    '+15555550002',
    'FACILITY_EMERGENCY'
FROM public.users
WHERE prisoner_id = '100001'
AND NOT EXISTS (SELECT 1 FROM public.contacts WHERE contact_id = 'CONTACT_004');

-- Insert contacts for Jane Smith (prisoner_id = '100002')
INSERT INTO public.contacts (contact_id, associated_inmate_id, full_name, phone_number, relationship_type)
SELECT
    'CONTACT_005',
    id,
    'Robert Smith',
    '+15551112222',
    'FAMILY'
FROM public.users
WHERE prisoner_id = '100002'
AND NOT EXISTS (SELECT 1 FROM public.contacts WHERE contact_id = 'CONTACT_005');

INSERT INTO public.contacts (contact_id, associated_inmate_id, full_name, phone_number, relationship_type)
SELECT
    'CONTACT_006',
    id,
    'Legal Aid Clerk',
    '+15553334444',
    'LAWYER'
FROM public.users
WHERE prisoner_id = '100002'
AND NOT EXISTS (SELECT 1 FROM public.contacts WHERE contact_id = 'CONTACT_006');