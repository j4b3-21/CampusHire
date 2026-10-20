# ADR-005: File storage and bulk Excel/CSV data exchange

Status: Accepted (2026-10-20)

## Context

Placement offices live in spreadsheets: student master lists from the academic section, shortlists from
recruiters, offer lists for reports. The platform must import and export thousands of rows reliably, explain every
rejected row, never leave a half-applied import without telling the user, and stay responsive while doing it.
Records also reference files (resumes, offer letters, photos) through `FILE_REFERENCE` / `IMAGE_REFERENCE` fields
(ADR-001), which so far had no storage behind them.

## Decision

### Files (`files` module)

- `stored_file (id, organization_id, storage_key, original_name, content_type, size_bytes, sha256, status,
  created_at, created_by)`; content lives in an S3-compatible object store (MinIO locally and on-prem), behind a
  `FileStorage` port with an S3 adapter and a filesystem adapter (tests, single-node installs without MinIO).
- Upload: `POST /api/v1/orgs/{orgId}/files` (multipart, max 25 MB). The content type is **sniffed from magic
  bytes** and must be on an allow-list (PDF, PNG, JPEG, WebP, XLSX, CSV, DOCX); the client-declared type and the
  file name are never trusted for storage keys (`<orgId>/<yyyy>/<mm>/<uuid>`).
- Download: `GET /api/v1/orgs/{orgId}/files/{id}` streams the object with `Content-Disposition: attachment` and
  `X-Content-Type-Options: nosniff`. Allowed for the uploader, organization admins, and callers who can read (with
  their record scope, ADR-004) a record that references the file.
- Record writes validate that a referenced `fileId` exists in the same organization.

### Data exchange (`dataexchange` module)

- **Templates**: `GET /orgs/{orgId}/modules/{key}/import-template?format=xlsx|csv` - one column per writable field
  (header = field label), a second sheet documents type, required flag, allowed options and an example.
- **Preview**: `POST /orgs/{orgId}/modules/{key}/imports/preview` (multipart) returns detected headers, the first
  20 rows and a suggested column-to-field mapping (exact api key, then case/space-insensitive label match).
- **Import job**: `POST /orgs/{orgId}/modules/{key}/imports` (multipart file + `{mode: CREATE|UPSERT,
  matchField, mapping, dryRun}`) stores the file, creates an `import_job` row and returns `202` with the job.
  - Workers claim jobs with `SELECT ... FOR UPDATE SKIP LOCKED` (safe with several API replicas) and run them on
    virtual threads.
  - XLSX is read with the POI streaming (SAX) reader, CSV with Commons CSV; memory stays flat for large files.
  - Cell values are converted per field type (Excel serial dates, `yes/no/1/0` booleans, select options by value or
    label, `LOOKUP` by the target module's unique field such as department code `CSE`), then validated by the same
    `RecordOperations` path as the API (types, required, unique, lookups, field security).
  - Rows are written in batches of 500 per transaction; a failing row is recorded and skipped, never aborting the
    batch. `dryRun` validates everything and writes nothing.
  - `UPSERT` matches on a unique field (`roll_number`, `email`) and patches only mapped columns.
  - Progress (`total`, `processed`, `created`, `updated`, `failed`) is persisted every batch; the UI polls
    `GET /imports/{jobId}`. `GET /imports/{jobId}/errors?format=xlsx` returns the failed rows with their original
    values plus `row` and `error` columns, so users fix and re-upload just that file.
  - Limits: 50,000 rows and 25 MB per file; one running job per module per organization.
- **Export**: `GET /orgs/{orgId}/modules/{key}/export?format=xlsx|csv` + the record list filters/sort/search.
  Streams (SXSSF window of 500 rows, CSV writer) honoring record scope and hidden fields. Cells starting with
  `= + - @ \t \r` are prefixed with `'` (CSV/formula injection).

## Consequences

- Imports are idempotent when run in `UPSERT` mode, observable, resumable by re-upload of the error file, and
  cannot exhaust memory or block request threads.
- Validation logic exists once (record engine); importers only translate cells to canonical JSON.
- MinIO is an additional service in deployments; installs without it can use the filesystem adapter on a single
  node.
