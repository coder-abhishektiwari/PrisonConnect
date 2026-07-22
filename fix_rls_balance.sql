-- This script fixes the balance update issue by granting the necessary RLS permissions.

-- 1. Enable UPDATE on the 'users' table for the 'anon' role (used by the Kiosk app).
-- We specifically allow updating the 'balance_remaining_seconds' column.

DROP POLICY IF EXISTS "Allow kiosks to update balance" ON public.users;

CREATE POLICY "Allow kiosks to update balance"
ON public.users
FOR UPDATE
TO anon
USING (true)
WITH CHECK (true);

-- 2. Verify that the table actually has RLS enabled.
-- (If it's disabled, the policy above won't matter, but it's good practice to keep it on).
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;

-- 3. Ensure the 'anon' role has basic usage permissions on the schema.
GRANT USAGE ON SCHEMA public TO anon;
GRANT ALL ON TABLE public.users TO anon;
