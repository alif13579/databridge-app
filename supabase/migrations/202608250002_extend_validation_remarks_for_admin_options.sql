-- Extends validation_remarks from a plain English->Bangla lookup helper into
-- the full remark-option store: this is where an admin's new/edited remark
-- option (via a future Supabase-backed ConfigRemarksFragment) will live,
-- replacing Firebase's config/remarks_worker and config/remarks_call_center.
--
-- Every field here mirrors what ConfigRemarksFragment already reads/writes
-- in Firebase (id, text_bn, text_en, target_status, template_id, priority,
-- instruction_type, instruction_text) — see that file's ConfigState.Remark
-- shape and saveRemarks(). Nothing about the admin UI itself is built by
-- this migration; this only prepares the table it will read from and write
-- to.

-- remarks_en was the primary key (one row per unique English text, no
-- concept of "which app's list this belongs to"). That doesn't hold once
-- Worker and Call Center are stored in the same table: the same English
-- text can be a valid option in both scopes, each with its own
-- target_status/priority/instructions — CC's "Not Answering" and Worker's
-- "Not Answering" are different rows, not one shared row. So the primary
-- key moves to a new `id` column (mirrors Firebase's random 6-char id),
-- and remarks_en's uniqueness becomes scoped to (source, remarks_en)
-- instead of being globally unique.
alter table public.validation_remarks drop constraint validation_remarks_pkey;
alter table public.validation_remarks add column id text default gen_random_uuid()::text;
update public.validation_remarks set id = gen_random_uuid()::text where id is null;
alter table public.validation_remarks alter column id set not null;
alter table public.validation_remarks add constraint validation_remarks_pkey primary key (id);

-- source: which app's remark list this row belongs to. Uses 'CC'/'WORKER' —
-- matching validations.source's existing naming (see the write action's
-- `row.source !== 'CC' && row.source !== 'WORKER'` check) rather than
-- Firebase's RemarkScope enum names (WORKER/CALL_CENTER), so both tables'
-- source columns speak the same vocabulary and one column doesn't need
-- translating to compare against the other. 'BOTH' is not a value here —
-- a remark usable in both scopes is simply two rows sharing an English
-- text (matching how Firebase already required a full separate entry
-- under config/remarks_worker AND config/remarks_call_center for the same
-- wording, since target_status/priority/instructions could differ).
alter table public.validation_remarks add column source text not null default 'CC'
  check (source in ('WORKER', 'CC'));
alter table public.validation_remarks alter column source drop default;

-- The upsert this table already receives on every remark write (Edge
-- Function's upsertRemarkLabel(), keyed only on remarks_en) predates the
-- source split -- it must move to (source, remarks_en) as its conflict
-- target so it stops colliding across scopes. That's an app-code change
-- alongside this migration, not something this migration itself can do.
alter table public.validation_remarks add constraint validation_remarks_source_en_unique
  unique (source, remarks_en);

-- target_status: which parcel status selecting this remark applies.
-- Firebase defaulted this to the enclosing status-group's key when a row
-- didn't set its own target_status (see loadRemarks()'s `?: key` fallback)
-- -- there's no equivalent grouping concept here, so every row must carry
-- its own explicit target_status once the admin UI starts writing here.
alter table public.validation_remarks add column target_status text not null default '';

-- template_id: optional linked WhatsApp template (config/whatsappTemplates
-- id) -- kept as a bare id/text reference, same as Firebase, rather than a
-- foreign key, since WhatsApp templates are not being migrated here.
alter table public.validation_remarks add column template_id text not null default '';

-- priority: sort order for the option chips/popup, higher shows first
-- (mirrors ConfigRemarksFragment's `sortedByDescending { it.priority }`).
alter table public.validation_remarks add column priority integer not null default 0;

-- instruction_type / instruction_text: the fixed "None / On Hold / Return"
-- delivery-agent instruction pair a remark can carry. Stored as free text
-- (not an enum) to match Firebase's stored values exactly and avoid a
-- migration-time mapping step -- ConfigState.INSTRUCTION_TYPES on the
-- Kotlin side remains the source of truth for which values are valid.
alter table public.validation_remarks add column instruction_type text not null default '';
alter table public.validation_remarks add column instruction_text text not null default '';

-- is_active: supports deactivating an option (hidden from the picker, but
-- still resolvable for Bangla lookup on already-saved remarks that used
-- it) as an alternative to hard-deleting it. Both are valid removal paths
-- per the earlier discussion -- an admin UI can choose either per-remark.
alter table public.validation_remarks add column is_active boolean not null default true;
