# CampusHire analytics and placement report API

Placement analytics for staff (PRD stories T7 and C2). Every endpoint is under
`/api/v1/orgs/{orgId}/campushire` and requires a bearer token.

## Access

| Caller | Result |
|--------|--------|
| Organization `OWNER` / `ADMIN` | All departments |
| Member with org role `tpo` | All departments |
| Member with org role `coordinator` (membership attribute `department`) | Only the coordinator's department(s) |
| `student`, `recruiter`, or any other role | `403 access_denied` |
| Not a member of the organization | `404 not_found` |

Authorization uses the caller's `READ` grant on the `student` module (`ModuleAccess`). A coordinator's
`ATTRIBUTE department` scope becomes a department filter on every query. If a coordinator passes a `departmentId`
outside their scope, the result is empty (all zero counts), not an error. Any other scope fails closed with `403`.

## Common query parameters

| Parameter | Type | Meaning |
|-----------|------|---------|
| `batchId` | UUID | Only students of this batch |
| `departmentId` | UUID | Only students of this department (intersected with a coordinator's scope) |
| `from` | ISO date | Offers and applications created on or after this day |
| `to` | ISO date | Offers and applications created on or before this day (inclusive) |

Dates are calendar days in the institute's time zone (`campushire.analytics.zone`, default `Asia/Kolkata`).
`from` after `to` is `400 validation_failed`.

## Definitions

- **Students in scope**: non-deleted rows of `campushire_student_projection` matching the organization, department
  and batch filters. Offers and applications are joined to these students, so they follow the student's current
  department (the same rule the `campushire_placement_stats` view uses). Offers and applications of deleted students
  are excluded.
- **Participating students**: students in scope whose `placement_status` is not `opted_out` or `higher_studies`.
- **Counted offer**: a non-deleted offer with status `extended` or `accepted`. Declined and revoked offers are not
  counted. The report's Offers sheet still lists them.
- **Placed students**: distinct students in scope with at least one counted offer.
- **Placement rate**: placed / participating, rounded to 4 decimals and capped at 1. It is 0 when no student
  participates.
- **CTC values** (`highestCtc`, `averageCtc`, `medianCtc`) are in INR with 2 decimals, computed over counted offers.
  They are `null` when there are none. The median is `percentile_cont(0.5)`.
- **Active drives**: drives with status `published`, `registration_closed` or `in_progress`.
- **Companies visited**: distinct companies with a drive in status `in_progress` or `completed`.
- Drive-based numbers (`activeDrives`, `companiesVisited`, `drives`) are organization-wide. Drives are not
  department-scoped, and coordinators can read all of them.
- `refreshedAt` is when the numbers were computed. A cached response keeps its original time.

Every number is computed live from the typed projections, so it matches a manual count exactly. The projections
themselves are updated asynchronously from record events, typically well under a second after a change (ADR-003).

## Caching

Responses are cached in Redis for 60 s (`campushire.analytics.cache.ttl`). Cache keys look like
`campushire:analytics:v1:{orgId}:{endpoint}:{hash}`, where the hash covers the department scope and every
parameter. Two users with the same scope and parameters share an entry. Redis is optional:

- A read that takes longer than `campushire.analytics.cache.timeout` (default 150 ms) is abandoned.
- After any Redis error, the cache is bypassed for `campushire.analytics.cache.retry-after` (default 30 s).
- `campushire.analytics.cache.enabled=false` turns caching off.

Reports are never cached and are sent with `Cache-Control: no-store`. Metrics: counter `campushire.analytics.cache`
tagged `result=hit|miss|error`.

The examples below use a data set with 9 students in two departments, 5 counted offers and 11 applications.

## `GET /analytics/overview`

```json
{
  "totalStudents": 9,
  "participatingStudents": 7,
  "placedStudents": 4,
  "placementRate": 0.5714,
  "offers": 5,
  "acceptedOffers": 3,
  "highestCtc": 2600000.00,
  "averageCtc": 1210000.00,
  "medianCtc": 1000000.00,
  "activeDrives": 2,
  "applications": 11,
  "companiesVisited": 2,
  "refreshedAt": "2026-10-20T12:00:00.123Z"
}
```

## `GET /analytics/departments`

One entry per department that has students in scope, sorted by name. Students without a department appear as
`departmentId: null, departmentName: null`. Names are read from the department records in one batch.

```json
{
  "departments": [
    {
      "departmentId": "0b9a4c4e-5d7f-4a59-9a43-3c1d6d1d2f10",
      "departmentName": "Computer Science",
      "totalStudents": 5,
      "participatingStudents": 3,
      "placedStudents": 2,
      "placementRate": 0.6667,
      "offers": 3,
      "highestCtc": 1200000.00,
      "averageCtc": 1000000.00,
      "medianCtc": 1000000.00
    },
    {
      "departmentId": "6f1e2a3b-7c8d-4e9f-8a0b-1c2d3e4f5a6b",
      "departmentName": "Electronics",
      "totalStudents": 4,
      "participatingStudents": 4,
      "placedStudents": 2,
      "placementRate": 0.5,
      "offers": 2,
      "highestCtc": 2600000.00,
      "averageCtc": 1525000.00,
      "medianCtc": 1525000.00
    }
  ],
  "refreshedAt": "2026-10-20T12:00:00.123Z"
}
```

## `GET /analytics/companies?limit=10`

Companies with at least one counted offer, ranked by offers (ties: higher `highestCtc` first). `limit` is between
1 and 100 (default 10); anything else is `400`. `drives` counts the company's drives that are not `draft` or
`cancelled`.

```json
{
  "companies": [
    {
      "companyId": "9d8c7b6a-5f4e-4d3c-2b1a-0f9e8d7c6b5a",
      "companyName": "Acme Systems",
      "offers": 3,
      "highestCtc": 2600000.00,
      "averageCtc": 1533333.33,
      "drives": 1
    },
    {
      "companyId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
      "companyName": "Beta Labs",
      "offers": 2,
      "highestCtc": 1000000.00,
      "averageCtc": 725000.00,
      "drives": 1
    }
  ],
  "refreshedAt": "2026-10-20T12:00:00.123Z"
}
```

## `GET /analytics/ctc-distribution`

Counted offers with a CTC, bucketed in LPA (lakh per annum, CTC / 100,000). Lower bounds are inclusive and upper
bounds are exclusive. The last bucket is open-ended (`maxLpa: null`).

```json
{
  "unit": "LPA",
  "offers": 5,
  "buckets": [
    { "label": "0-3",   "minLpa": 0,  "maxLpa": 3,    "count": 0 },
    { "label": "3-6",   "minLpa": 3,  "maxLpa": 6,    "count": 1 },
    { "label": "6-10",  "minLpa": 6,  "maxLpa": 10,   "count": 1 },
    { "label": "10-15", "minLpa": 10, "maxLpa": 15,   "count": 2 },
    { "label": "15-25", "minLpa": 15, "maxLpa": 25,   "count": 0 },
    { "label": "25+",   "minLpa": 25, "maxLpa": null, "count": 1 }
  ],
  "refreshedAt": "2026-10-20T12:00:00.123Z"
}
```

## `GET /analytics/trend?interval=month`

Counted offers and applications per calendar month for 12 months. The window ends with the current month, or with
the month of `to` when given. Months without activity are included with zeros. `interval` only supports `month`;
anything else is `400`.

`GET /analytics/trend?interval=month&to=2026-06-30`:

```json
{
  "interval": "month",
  "points": [
    { "period": "2025-07", "offers": 0, "applications": 0 },
    { "period": "2025-08", "offers": 0, "applications": 0 },
    { "period": "2025-09", "offers": 0, "applications": 0 },
    { "period": "2025-10", "offers": 0, "applications": 0 },
    { "period": "2025-11", "offers": 0, "applications": 0 },
    { "period": "2025-12", "offers": 0, "applications": 0 },
    { "period": "2026-01", "offers": 0, "applications": 0 },
    { "period": "2026-02", "offers": 0, "applications": 0 },
    { "period": "2026-03", "offers": 1, "applications": 5 },
    { "period": "2026-04", "offers": 1, "applications": 2 },
    { "period": "2026-05", "offers": 2, "applications": 4 },
    { "period": "2026-06", "offers": 1, "applications": 0 }
  ],
  "refreshedAt": "2026-10-20T12:00:00.123Z"
}
```

## `GET /analytics/drives/{driveId}/funnel`

Applications of one drive by their **current** stage, in workflow order. A drive that does not exist in the
organization is `404`.

- **Forward stages** (`applied` to `joined`):
  - `count`: applications currently at this stage.
  - `reached`: applications currently at this stage or any later forward stage. `applied` reaches every
    application.
  - `conversionRate`: `reached` divided by the previous stage's `reached`.
- **Exit stages** (`rejected`, `withdrawn`, then any custom workflow states, sorted):
  - `reached` equals `count`.
  - `conversionRate`: share of all applications.
- `offerRate`: `reached` of `offered` divided by all applications.

The projection only stores the current stage, so a rejected application counts toward `applied` but not toward the
stage it was rejected at.

```json
{
  "driveId": "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f",
  "driveTitle": "Acme SDE",
  "companyId": "9d8c7b6a-5f4e-4d3c-2b1a-0f9e8d7c6b5a",
  "companyName": "Acme Systems",
  "applications": 7,
  "stages": [
    { "stage": "applied",     "count": 1, "reached": 7, "conversionRate": 1.0 },
    { "stage": "shortlisted", "count": 0, "reached": 4, "conversionRate": 0.5714 },
    { "stage": "assessment",  "count": 0, "reached": 4, "conversionRate": 1.0 },
    { "stage": "interview",   "count": 1, "reached": 4, "conversionRate": 1.0 },
    { "stage": "offered",     "count": 2, "reached": 3, "conversionRate": 0.75 },
    { "stage": "joined",      "count": 1, "reached": 1, "conversionRate": 0.3333 },
    { "stage": "rejected",    "count": 1, "reached": 1, "conversionRate": 0.1429 },
    { "stage": "withdrawn",   "count": 1, "reached": 1, "conversionRate": 0.1429 }
  ],
  "offerRate": 0.4286,
  "refreshedAt": "2026-10-20T12:00:00.123Z"
}
```

## `GET /reports/placement?format=xlsx|pdf`

Downloads the placement report for the caller's scope and the common filters. `format` defaults to `xlsx`. Any
other value is `400`. Data is read in one repeatable-read snapshot, so all parts of a report agree with each other.

| Format | Content-Type | Filename |
|--------|--------------|----------|
| `xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | `placement-report-yyyyMMdd.xlsx` |
| `pdf` | `application/pdf` | `placement-report-yyyyMMdd.pdf` |

The response has `Content-Disposition: attachment; filename="placement-report-20261020.xlsx"`. The date is today
in the institute's time zone.

**Excel** (Apache POI SXSSF streaming workbook), sheets in this order:

1. `Summary`: title, generation time, filters, then the overview metrics as `Metric | Value` rows (CTC in LPA)
   followed by the CTC distribution.
2. `Departments`: `Department | Students | Participating | Placed | Placement rate | Offers | Highest CTC (LPA) |
   Average CTC (LPA) | Median CTC (LPA)`.
3. `Companies`: every company with counted offers. Columns: `Company | Offers | Drives | Highest CTC (LPA) |
   Average CTC (LPA)`.
4. `Offers`: every non-deleted offer of the students in scope, any status, newest first. Columns:
   `Roll number | Student | Department | Company | CTC (LPA) | Type | Status | Date`.

Numbers are numeric cells and dates are date cells. Text cells starting with `=`, `+`, `-`, `@`, tab or carriage
return are prefixed with `'` to prevent formula injection.

**PDF** (OpenPDF, A4):

- Page 1: title, generation time and filters, nine key-figure tiles and the CTC distribution.
- Page 2: the department table and the top 10 companies.
- Each page has a footer with the page number.
