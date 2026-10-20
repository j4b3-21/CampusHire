# ADR-001: Dynamic record storage - relational metadata + JSONB values

Status: Accepted (2026-10-20)

## Context

BM4A must store business data for modules that tenants define at runtime (for example "ExampleAsset",
"Student", "Lead") without new Java classes, tables, or a redeploy. The data must be validated, filterable,
sortable, paginated, versioned, tenant-isolated, and must survive schema evolution.

Options considered:

| Option | Pros | Cons |
|--------|------|------|
| A. Table per module (runtime DDL) | Native types, constraints, indexes | Runtime DDL per tenant, migrations at scale, lock contention, privilege escalation risk |
| B. Pure EAV (`record_value(record_id, field_id, value_*)`) | Typed columns, simple indexes | N joins per read, painful pagination/sorting, heavy write amplification, hard to version |
| C. One JSONB document per record | Single-row reads/writes, GIN indexes, cheap versioning | No DB type checking per field, expression indexes needed for fast typed sorts |
| D. Hybrid: relational metadata + JSONB values + targeted side tables | Combines C with relational integrity where it matters | Two places to look for "truth"; must document |

## Decision

Option D.

1. **Relational tables** hold everything structural: `organization`, `membership`, `platform_user`,
   `module_definition`, `field_definition`, `module_definition_version`, `relationship_definition`,
   `record_link`, `audit_event`. These have foreign keys, check constraints and unique indexes.
2. **`record` table** holds one row per business record with stable system columns
   (`id`, `organization_id`, `module_id`, `version`, `definition_version`, `created_at/by`, `updated_at/by`,
   `deleted_at/by`) and a single `data jsonb` column with the field values. `data` must be a JSON object
   (`CHECK (jsonb_typeof(data) = 'object')`).
3. **Schema and values are never mixed.** The schema lives in `field_definition` rows; `data` only contains
   values keyed by the field's immutable `api_key`. System fields are never stored inside `data`.
4. **Targeted side tables** only where a real requirement exists:
   - `record_unique_value(organization_id, field_id, normalized_value, record_id)` with a unique index
     enforces `unique` fields atomically. JSONB alone cannot guarantee per-field uniqueness without a
     per-field expression index (runtime DDL, rejected above).
   - `record_link` stores many-to-many / one-to-many links with cardinality-specific unique indexes.
   - `record_version` stores immutable snapshots for history and restore.
   No general EAV value tables are created. They will be added only for measured reporting needs.

## Canonical value encoding

Validation (`records` module, using `metadata` definitions) converts input to one canonical JSON shape:

| Field type | Canonical JSON | Notes |
|------------|----------------|-------|
| `TEXT`, `LONG_TEXT` | string | trimmed; `minLength`/`maxLength` (defaults 255 / 10000) |
| `EMAIL` | string | basic RFC-style format check, max 254 |
| `PHONE` | string | `+`, digits, spaces, `-().`; 4-32 chars |
| `URL` | string | absolute `http`/`https` URI, max 2048 |
| `INTEGER` | number (integral) | 64-bit; `min`/`max` |
| `DECIMAL` | string, e.g. `"12.50"` | arbitrary precision, never binary floating point; `scale`, `min`, `max` |
| `CURRENCY` | `{"amount": "12.50", "currency": "INR"}` | ISO 4217 code; optional `currencies` allow-list |
| `BOOLEAN` | `true`/`false` | |
| `DATE` | `"2026-10-20"` | ISO-8601 local date |
| `DATETIME` | `"2026-10-20T06:15:00Z"` | normalized to UTC `Instant` |
| `SINGLE_SELECT` | string option value | must be an active option |
| `MULTI_SELECT` | array of distinct option values | `maxSelections` |
| `JSON` | any JSON value | `maxBytes` (default 16 KiB) |
| `FILE_REFERENCE`, `IMAGE_REFERENCE` | `{"fileId": "...", "name"?: "...", "contentType"?: "..."}` | reference only; file storage is a later milestone. Images require `image/*` when `contentType` is given |
| `LOOKUP` | UUID string of the target record | target must exist, be active, same tenant, and in `targetModuleKey` |

Absent and `null` mean "no value"; keys with no value are removed from `data`. Defaults apply on create only.

## Querying

- Every query is scoped by `organization_id` and `module_id` and excludes soft-deleted rows unless asked.
- Filtering and sorting are **allow-listed by metadata**: only fields flagged `filterable` / `sortable`, and
  only operators valid for the field type. The SQL is assembled from constant fragments; field keys and values
  are always bound parameters (`data -> :key`, `CAST(:value AS jsonb)`), never concatenated.
- Equality and membership filters use JSONB containment (`data @> :json`), served by
  `GIN (data jsonb_path_ops)`. Range filters and typed sorts cast the extracted value
  (`(data ->> :key)::numeric`, `::timestamptz`, `::date`); these are safe because values are canonical.
- Pagination is offset based (`page`, `size` <= 100) with a deterministic `id` tiebreaker.

Limitations: range filters and typed sorts on large modules are sequential within the tenant/module scope
(index `record(organization_id, module_id, created_at)` narrows the scan). Per-field expression indexes or
typed side tables will be introduced only when measurements justify them, through migrations owned by the
platform (never tenant-triggered DDL).

## Concurrency, history and deletion

- `version` increments on every change. Writes require `If-Match: "<version>"`; missing -> 428, stale -> 412.
- Each change writes an immutable `record_version` snapshot (create, update, delete, restore, version-restore).
- Delete is soft (`deleted_at`). Restore re-checks unique values. Hard deletion/retention is a later feature.

## Schema evolution

- Field `api_key` and `field_type` identity are immutable; labels and settings can change freely.
- Type changes are allowed only along a compatibility table that keeps every existing value valid
  (`TEXT -> LONG_TEXT`, `INTEGER -> DECIMAL`, `SINGLE_SELECT -> MULTI_SELECT` is **not** allowed because
  stored values differ in shape). Anything else requires a new field plus a backfill.
- Fields are never hard-deleted. `DEPRECATED` fields stay visible but reject new values; `ARCHIVED` fields are
  hidden from the API, reject writes, and their stored values are preserved (full-replace updates keep them).
- Select options cannot be removed, only marked `deprecated`; deprecated values remain valid on records that
  already hold them.
- Every metadata change increments the module `definition_version` and stores a snapshot in
  `module_definition_version`. Records remember the `definition_version` they were last validated against,
  which enables future backfills ("re-validate every record written before version N").
- Formula/computed fields and conditional rules are **not** implemented. No tenant-supplied expression,
  script, regex, or SQL is evaluated. A future `computed` field will use a small, side-effect-free expression
  language evaluated in a sandbox; the extension point is the `FieldType` enum plus the validator registry.

## Consequences

- One generic record API serves every module; no code or DDL per module.
- PostgreSQL enforces structure, tenant scope, uniqueness and referential integrity for links; the application
  enforces per-field value rules at the boundary.
- Reporting-grade analytics on JSONB will eventually need projections or typed side tables (Phase 8).
