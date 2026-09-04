-- 202609040006: drop claims.attachment_url, claims.attachment_name
-- (0 rows used them — verified before dropping; attachments jsonb is the
-- store now) and claims.priority (every row 'normal', never set in UI).
-- Applied live via Management API (db query).

ALTER TABLE public.claims DROP COLUMN IF EXISTS attachment_url;
ALTER TABLE public.claims DROP COLUMN IF EXISTS attachment_name;
ALTER TABLE public.claims DROP COLUMN IF EXISTS priority;
