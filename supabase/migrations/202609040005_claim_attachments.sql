-- 202609040005: multi-attachment support for claims.
--
-- Old single columns (attachment_url/attachment_name) stay for existing
-- rows; new writes go to attachments (jsonb array of
-- {key, name, size}). Applied live via Management API (db query).

ALTER TABLE public.claims
ADD COLUMN IF NOT EXISTS attachments jsonb NOT NULL DEFAULT '[]'::jsonb;
