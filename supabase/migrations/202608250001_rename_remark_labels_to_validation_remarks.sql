-- Drops the validations_with_bn view (202608230002) — decided against a
-- server-side join. Android now does two direct PostgREST reads instead:
-- one to validations for the English remark (unchanged, zero-invocation
-- as before), one to validation_remarks for the Bangla lookup keyed by
-- that English text. Simpler mental model: validation_remarks is a plain
-- helper/lookup table, not something baked into how validations is read.
drop view if exists public.validations_with_bn;

-- Renames remark_labels -> validation_remarks (clearer name: this is a
-- helper table for validations' remark text, not a general label store),
-- and renames its columns to match the remarks_en / remarks_bn naming
-- used on the write side (SupabaseRemarkValidationWriter, the Edge
-- Function's `row.remarks_bn`).
--
-- category is added now, blank for every existing and new row until a
-- later pass starts assigning categories per remark — adding the column
-- here avoids a second migration once that categorization work starts.
alter table public.remark_labels rename to validation_remarks;
alter table public.validation_remarks rename column english_label to remarks_en;
alter table public.validation_remarks rename column bangla_label to remarks_bn;
alter table public.validation_remarks add column if not exists category text not null default '';

-- Renaming a table does not rename its RLS policies or their internal
-- target — the existing "remark_labels_select_authenticated" policy still
-- applies to this table under its new name, this just gives it a name
-- that matches. Functionally a no-op.
alter policy "remark_labels_select_authenticated" on public.validation_remarks
  rename to "validation_remarks_select_authenticated";
