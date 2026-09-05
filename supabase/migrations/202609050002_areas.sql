-- 202609050002: public.areas branch-wise area directory.
--
-- Replaces the two courier-wide Firebase directories (courier/areas/
-- delivery_area + pickup_area, which know neither branch nor zone):
--   branch_id  -> branches(branch_id), which branch this area belongs to
--   area_id    -> human-assigned id (unique per branch), like Store ID
--   name       -> display name
--   area_type  -> 'pickup' | 'delivery' | 'both' (which pickers list it)
--   zone       -> free-text zone/group label for area grip
-- Claims (From/To) and pickup Stores read their areas from here, scoped to
-- the claim's branch. Existing From/To/store area values are display snapshots
-- (names, not FKs), so old rows keep working untouched.
-- Public directory read (same pattern as stores/branches); writes go through
-- the directory Edge Function (admin/manager-gated, service-role client).
-- Applied live via Management API (db query).

CREATE TABLE IF NOT EXISTS public.areas (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  branch_id text NOT NULL REFERENCES public.branches(branch_id),
  area_id text NOT NULL,
  name text NOT NULL,
  area_type text NOT NULL DEFAULT 'both' CHECK (area_type IN ('pickup', 'delivery', 'both')),
  zone text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (branch_id, area_id)
);
CREATE INDEX IF NOT EXISTS areas_branch_type_idx ON public.areas (branch_id, area_type);

grant select on public.areas to anon, authenticated;

drop policy if exists "areas_public_read" on public.areas;
create policy "areas_public_read"
  on public.areas
  for select to anon, authenticated
  using (true);
