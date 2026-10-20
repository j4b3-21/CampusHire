# CampusHire API

Domain endpoints of the CampusHire backend (`backend/`, modules under `com.campushire`). Generic data management
(students, companies, jobs, drives, follow-ups, ...) uses the BM4A record API (`/api/v1/orgs/{orgId}/modules/{module}/records`,
see [bm4a/docs/api/README.md](../../bm4a/docs/api/README.md)); the endpoints below cover operations with rules.

## Conventions

Everything from the BM4A guide applies: base path `/api/v1`, bearer tokens, `orgId` checked against the caller's
membership (unknown and foreign organizations are `404`), RFC 9457 problems with a stable `code`
(`{"type", "title", "status", "detail", "code", "errors"?: [{field, code, message}], "correlationId"}`), paging
`page`/`size` (max 100) with `{items, page, size, totalElements, totalPages}`, UTC ISO-8601 instants, `YYYY-MM-DD`
dates, strict JSON (unknown properties are `400`). Amounts are INR numbers (`1200000.00`). Responses carry `null` for
absent values.

Personas (the caller's org role, see `GET /campushire/me`):

| Persona | Membership | Org role | Notes |
|---------|------------|----------|-------|
| Admin / placement officer | `OWNER` / `ADMIN` | `tpo` (set by setup) | Institute-wide access to everything |
| Coordinator | `MEMBER` | `coordinator`, attribute `department` | Students, applications and offers of their departments |
| Student | `MEMBER` | `student`, attributes `student`, `department`, `batch` | Linked through onboarding |
| Recruiter | `MEMBER` | `recruiter`, attribute `company` | Their company's drives and applicants |

"Staff" means admins, placement officers and coordinators.

| Method and path | Who |
|-----------------|-----|
| `POST /orgs/{orgId}/campushire/setup`, `GET .../setup` | admin (`METADATA_MANAGE`) / member |
| `GET /campushire/onboarding`, `POST /campushire/onboarding/claim` | any signed-in user |
| `GET /orgs/{orgId}/campushire/me` | member |
| `GET /orgs/{orgId}/campushire/dashboard` | member |
| `GET .../campushire/drives/{driveId}/eligibility/preview` | staff |
| `POST .../campushire/drives/{driveId}/publish\|close\|start\|complete\|cancel` | drive UPDATE (placement officers) |
| `GET .../campushire/student/drives` | student |
| `POST .../campushire/drives/{driveId}/apply`, `/withdraw` | student |
| `GET .../campushire/drives/{driveId}/applicants`, `.../applicants/{applicationId}/resume` | staff, recruiter |
| `POST .../campushire/drives/{driveId}/applicants/transition`, `/transition/upload` | staff, recruiter (application UPDATE) |
| `POST .../campushire/offers` | offer CREATE (placement officers) |
| `POST .../campushire/offers/{offerId}/accept`, `/decline` | student owning the offer |
| `POST .../campushire/projections/rebuild` | admin (`METADATA_MANAGE`) |

Inviting staff: until the platform publishes an invitation API for application code (see "Inviting recruiters and
coordinators" below), use the platform endpoint `POST /orgs/{orgId}/invitations` with the presets shown there.

## Setup

### `POST /api/v1/orgs/{orgId}/campushire/setup`

Owners and admins (`METADATA_MANAGE`). Installs the CampusHire app pack (modules, layouts, roles `tpo`, `coordinator`,
`student`, `recruiter`, the application workflow, settings defaults) and gives the caller the `tpo` org role (the
membership role `OWNER`/`ADMIN` is kept). Idempotent: running it again changes nothing and reports `UP_TO_DATE`.
Body optional:

```json
{"allowedEmailDomains": ["iiitt.ac.in"]}
```

Domains are lower-cased, a leading `@` is dropped, duplicates are merged (max 20). They are stored in
`settings.campushire.allowedEmailDomains` when none are configured yet; when a list is configured already, a request
whose domains are all in it succeeds, anything else is `409 allowed_domains_configured` (change domains through
`PATCH /orgs/{orgId}` settings).

`200`:

```json
{
  "installed": true,
  "report": {
    "packKey": "campushire", "version": 1, "status": "INSTALLED", "previousVersion": null,
    "created": {"modules": ["department", "batch", "student", "..."], "fields": ["student.roll_number", "..."],
                "options": [], "layouts": ["department", "..."], "roles": ["tpo", "coordinator", "student", "recruiter"],
                "relationships": [], "workflows": ["application"], "settings": ["campushire"]},
    "skipped": []
  },
  "roleKey": "tpo",
  "allowedEmailDomains": ["iiitt.ac.in"]
}
```

`report.status`: `INSTALLED | UPGRADED | UP_TO_DATE`. Errors: `400 validation_failed` (`errors[].field` =
`allowedEmailDomains[i]`), `403 access_denied`, `404`, `409 allowed_domains_configured`, `409 pack_downgrade`.

### `GET /api/v1/orgs/{orgId}/campushire/setup`

Any member. `200`:

```json
{"installed": true, "version": 1, "installedAt": "2026-10-20T10:15:00Z", "installedBy": "uuid",
 "availableVersion": 1, "upgradeAvailable": false, "allowedEmailDomains": ["iiitt.ac.in"]}
```

Before setup: `{"installed": false, "version": null, "installedAt": null, "installedBy": null, "availableVersion": 1,
"upgradeAvailable": true, "allowedEmailDomains": []}`.

## Onboarding

### `GET /api/v1/campushire/onboarding`

Any signed-in user (no membership needed). Institutes whose allowed domains contain the domain of the caller's e-mail:

```json
{
  "email": "cs22b001@iiitt.ac.in",
  "emailVerified": true,
  "domain": "iiitt.ac.in",
  "organizations": [
    {"organizationId": "uuid", "name": "IIIT Trichy", "member": false, "roleKey": null, "linked": false,
     "studentRecordFound": true}
  ]
}
```

`studentRecordFound: false` -> show "contact the placement cell". `linked: true` -> the caller already claimed a
record there. An e-mail outside every institute's domains yields `organizations: []`.

### `POST /api/v1/campushire/onboarding/claim`

```json
{"organizationId": "uuid"}
```

Links the caller to the active student record whose `email` equals the caller's verified e-mail
(case-insensitive): grants membership `MEMBER` with role `student` and attributes `{student, department, batch}`, and
makes the caller the record's owner. Idempotent and safe under concurrent calls. `200`:

```json
{"organizationId": "uuid", "studentId": "uuid", "roleKey": "student",
 "attributes": {"student": "uuid", "department": "uuid", "batch": "uuid"}, "alreadyLinked": false}
```

| Status | `code` | When |
|--------|--------|------|
| 403 | `email_not_verified` | token without `email_verified` |
| 403 | `domain_not_allowed` | e-mail domain not in the institute's `allowedEmailDomains` |
| 403 | `membership_revoked` | the caller's membership was revoked |
| 404 | `student_not_found` | no student record with this e-mail ("contact the placement cell") |
| 404 | `not_found` | unknown organization |
| 409 | `already_member` | caller is a member with another role (staff, recruiter) |
| 409 | `student_already_claimed` | the record is linked to another account |

When a student's `department` or `batch` changes, the membership attributes follow within seconds.

## Me

### `GET /api/v1/orgs/{orgId}/campushire/me`

```json
{
  "organizationId": "uuid",
  "membershipRole": "MEMBER",
  "admin": false,
  "role": {"key": "student", "label": "Student"},
  "student": {"id": "uuid", "rollNumber": "CS22B001", "fullName": "Asha R", "email": "cs22b001@iiitt.ac.in",
              "departmentId": "uuid", "batchId": "uuid", "program": "btech", "placementStatus": "unplaced",
              "profileVerified": false, "version": 3},
  "departmentIds": ["uuid"],
  "companyIds": [],
  "attributes": {"student": "uuid", "department": "uuid", "batch": "uuid"},
  "capabilities": {
    "student": {"read": true, "create": false, "update": true, "delete": false, "readScope": "OWN",
                "hiddenFields": [], "readOnlyFields": ["cgpa", "..."]},
    "drive": {"read": true, "create": false, "update": false, "delete": false, "readScope": "ALL",
              "hiddenFields": [], "readOnlyFields": []}
  },
  "onboarding": {"packInstalled": true, "packVersion": 1, "status": "complete"}
}
```

`role` is `null` without an org role; `student` is set for students linked to an existing record. `capabilities` is
the platform's `/me/access` module map. `onboarding.status`: `setup_required` (pack not installed),
`student_not_linked` (student role without a record), `complete`.

## Drives (staff)

### `GET .../campushire/drives/{driveId}/eligibility/preview?sample=10`

Staff; coordinators only count students of their departments. Evaluates the drive's `eligibility` against every
student (projections, typically a second behind the records). `sample` 0-50 (default 10).

```json
{
  "driveId": "uuid",
  "eligibleCount": 412,
  "totalConsidered": 1180,
  "sample": [{"studentId": "uuid", "rollNumber": "CS22B001", "fullName": "Asha R", "departmentId": "uuid",
              "cgpa": 8.42}],
  "breakdown": {"byDepartment": [{"departmentId": "uuid", "eligible": 210, "considered": 400}]},
  "reasons": {"not_participating": 12, "cgpa_below_minimum": 530, "too_many_backlogs": 140,
              "department_not_eligible": 300, "batch_not_eligible": 0, "program_not_eligible": 0,
              "tenth_below_minimum": 3, "twelfth_below_minimum": 5, "offer_limit_reached": 61},
  "driveCtc": 1500000.00
}
```

`reasons` counts students failing each rule (a student may fail several). Errors: `400 validation_failed` (malformed
eligibility, `errors[].field` like `eligibility.minCgpa`), `403` (students, recruiters), `404`.

Eligibility JSON (drive field `eligibility`): `{"minCgpa": 7.0, "maxActiveBacklogs": 0, "departments": [ids],
"batches": [ids], "programs": ["btech"], "minTenth": 60, "minTwelfth": 60, "excludePlaced": true}`; absent rules do
not restrict. Offer rule: students holding `maxOffersPerStudent` active (extended/accepted) offers - or one, with
`excludePlaced` - are ineligible unless `allowDreamUpgrade` and the job CTC is at least `dreamCtcThreshold` and above
their best offer (`settings.campushire.policy`).

Reason codes (also used by the student list and apply): `not_participating`, `cgpa_below_minimum`,
`too_many_backlogs`, `department_not_eligible`, `batch_not_eligible`, `program_not_eligible`, `tenth_below_minimum`,
`twelfth_below_minimum`, `offer_limit_reached`, and `eligibility_invalid` (rules being edited; drive closed).

### `POST .../campushire/drives/{driveId}/publish|close|start|complete|cancel`

Requires UPDATE on drives (placement officers; coordinators get `403`). Optional `If-Match: "<version>"`; without it
the version read by the server is used. No body. Transitions of `drive_status`:

| Action | From | To |
|--------|------|----|
| `publish` | `draft` | `published` |
| `close` | `published` | `registration_closed` |
| `start` | `published`, `registration_closed` | `in_progress` |
| `complete` | `registration_closed`, `in_progress` | `completed` |
| `cancel` | `draft`, `published`, `registration_closed`, `in_progress` | `cancelled` |

Publishing validates the eligibility rules (stored back in canonical form), requires `registration_closes_at` in the
future, `registration_opens_at` (if set) before it and a `job`, and notifies every eligible student with a linked
account (in-app and e-mail). Cancelling notifies the applicants. `200` with `ETag`:

```json
{"id": "uuid", "version": 4, "title": "Acme SDE 2027", "driveStatus": "published",
 "registrationOpensAt": "2026-10-21T04:30:00Z", "registrationClosesAt": "2026-10-25T12:30:00Z", "notifiedStudents": 412}
```

Errors: `400 validation_failed` (`errors[].field`: `eligibility.*`, `registration_closes_at` with codes `required` /
`past`, `registration_opens_at` `order`, `job` `required`), `404`, `409 drive_status_conflict`, `412 version_conflict`.

## Students

### `GET .../campushire/student/drives?filter=open|upcoming|all&page=0&size=20`

Students linked to a record (`403 access_denied` for other personas, `403 student_not_linked` without a record).
`open` (default): published and registration open now, soonest deadline first; `upcoming`: published, registration not
open yet; `all`: published, registration closed, in progress and completed, latest deadline first.

```json
{
  "items": [{
    "id": "uuid", "title": "Acme SDE 2027", "driveStatus": "published",
    "company": {"id": "uuid", "name": "Acme"},
    "job": {"id": "uuid", "title": "SDE", "jobType": "full_time", "ctc": 1500000.00, "location": "Bengaluru"},
    "registrationOpensAt": "2026-10-21T04:30:00Z", "registrationClosesAt": "2026-10-25T12:30:00Z",
    "driveDate": "2026-11-02", "mode": "on_campus", "venue": "Main auditorium", "description": "...",
    "registrationOpen": true,
    "eligibility": {"eligible": false, "reasons": [{"code": "cgpa_below_minimum",
                                                     "message": "CGPA 6.8 is below the required 7.0"}]},
    "application": {"id": "uuid", "stage": "applied"}
  }],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

`application` is `null` before applying.

### `POST .../campushire/drives/{driveId}/apply`

Student; no body. One short transaction: drive must be `published` and the server time inside the registration
window; eligibility is checked against the current student record. `201` for a new application, `200` with the
existing one when the student applied before (double clicks, retries, concurrent calls), including after withdrawing.
`ETag` = application version.

```json
{"id": "uuid", "driveId": "uuid", "studentId": "uuid", "stage": "applied", "version": 1,
 "createdAt": "2026-10-22T09:00:00Z"}
```

| Status | `code` | When |
|--------|--------|------|
| 403 | `student_not_linked` / `access_denied` | not a linked student |
| 404 | `not_found` | drive not visible (draft, cancelled, unknown) |
| 409 | `drive_not_open` | not `published` |
| 409 | `registration_not_open` / `registration_closed` | outside the window |
| 409 | `not_eligible` | `errors: [{"field": "eligibility", "code": "<reason code>", "message": "..."}]` |
| 409 | `application_removed` | staff deleted the student's application for this drive |

### `POST .../campushire/drives/{driveId}/withdraw`

Student. Optional body `{"comment": "Going for higher studies"}` (max 2000). Workflow transition `withdraw`, allowed
from `applied` and `shortlisted`. `200`: the application (shape as apply, `stage: "withdrawn"`). `404` without an
application, `409 transition_not_allowed` otherwise (including already withdrawn).

## Applicants and pipeline (staff, recruiters)

### `GET .../campushire/drives/{driveId}/applicants?stage=&q=&sort=&page=&size=`

The drive must be readable (recruiters: their company, else `404`); the caller's application READ scope applies
(coordinators see their departments' applicants only). Students get `403`. `q` searches roll number, name and e-mail;
`sort`: `rollNumber` (default), `fullName`, `cgpa`, `appliedAt`, `updatedAt`, `stage`, `-` prefix for descending
(`400 invalid_sort` otherwise). Phone and date of birth are never returned.

```json
{
  "items": [{
    "applicationId": "uuid", "version": 3, "stage": "shortlisted",
    "appliedAt": "2026-10-22T09:00:00Z", "updatedAt": "2026-10-23T11:00:00Z",
    "student": {"id": "uuid", "rollNumber": "CS22B001", "fullName": "Asha R", "email": "cs22b001@iiitt.ac.in",
                "departmentId": "uuid", "batchId": "uuid", "program": "btech", "cgpa": 8.42, "activeBacklogs": 0,
                "totalBacklogs": 1, "tenthPercentage": 91.2, "twelfthPercentage": 88.0, "placementStatus": "unplaced",
                "skills": "Java, SQL", "resume": {"fileId": "uuid", "name": "cv.pdf"}},
    "eligibleNow": {"eligible": true, "reasons": []}
  }],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

`eligibleNow` re-evaluates the drive's rules against the student's current record (warning badge when a record
changed after applying; `null` while the student is not projected yet). New applications appear within about a
second (projection).

### `GET .../campushire/drives/{driveId}/applicants/{applicationId}/resume`

Same access rules as the applicant list (recruiters cannot use the platform's `GET /orgs/{orgId}/files/{id}` for
student files, because they cannot read student records). Streams the resume currently on the student record with
`Content-Disposition: attachment`; audited as `campushire.resume.downloaded`. `404` when the application is not
visible, belongs to another drive, or the student has no resume.

### `POST .../campushire/drives/{driveId}/applicants/transition`

```json
{"applicationIds": ["uuid"], "rollNumbers": ["CS22B001", "cs22b002"], "to": "shortlisted", "comment": "Round 1"}
```

`applicationIds` and/or `rollNumbers` (case-insensitive, at most 2000 in total), `to` one of the application stages,
`comment` optional (max 2000; required by transitions with `requireComment`). Each application moves in its own
transaction on behalf of the caller: their UPDATE scope, field policy and the workflow's role rules apply. Always `200`
when the request itself is valid:

```json
{
  "results": [
    {"applicationId": "uuid", "rollNumber": null, "ok": true, "stage": "shortlisted", "version": 2, "error": null},
    {"applicationId": "uuid", "rollNumber": "cs22b002", "ok": false, "stage": null, "version": null,
     "error": {"code": "transition_not_allowed", "message": "Moving from 'interview' to 'shortlisted' is not allowed. ..."}}
  ],
  "succeeded": 1, "failed": 1, "unknownRollNumbers": ["CS22B999"]
}
```

Item errors: `not_found` (not an application of this drive or outside the caller's scope), `transition_not_allowed`,
`comment_required`, `version_conflict`. Request errors: `400 validation_failed` (no IDs/roll numbers, too many,
unknown `to`), `403` (no UPDATE on applications, students), `404` (drive not readable).

Stages: `applied -> shortlisted -> assessment -> interview -> offered -> joined`, plus `rejected` and `withdrawn`;
see the workflow (`GET /orgs/{orgId}/modules/application/workflow`) for who may use which transition.

### `POST .../campushire/drives/{driveId}/applicants/transition/upload`

`multipart/form-data`: `file` (XLSX, XLS or CSV; first sheet; 25 MB), `to`, optional `comment`. The roll number column
is found by its header within the first 10 rows (`Roll`, `Roll number`, `Roll No.`, `roll_no`, `Register number`,
... - case, spaces and punctuation ignored); CSV delimiter `,`, `;` or tab is detected. Blank cells are skipped,
duplicates removed, at most 5000 rows. Response as `/transition`. Errors: `400 file_empty`, `400 roll_column_missing`,
`400 no_roll_numbers`, `400 too_many_rows`, `400 unreadable_file`.

## Offers

### `POST /api/v1/orgs/{orgId}/campushire/offers`

Requires CREATE on offers (placement officers; recruiters `403`).

```json
{"applicationId": "uuid", "ctc": 1200000, "offerType": "full_time", "joiningDate": "2027-07-01",
 "respondBy": "2026-11-10", "offerLetterFileId": "uuid", "override": false}
```

`ctc` (> 0, INR) and `applicationId` are required; `offerType` `full_time | internship | ppo`;
`offerLetterFileId` is a file uploaded through `POST /orgs/{orgId}/files`. In one transaction: the offer record is
created with student, company, drive and department copied from the application (`offer_status: extended`, owner =
the student's account), the application moves to `offered` (unless already `offered`/`joined`) and an `unplaced`
student becomes `placed`; the student is notified. Policy: a student holding `maxOffersPerStudent` active offers may
only receive a dream upgrade (CTC >= `dreamCtcThreshold` and above their best offer, when `allowDreamUpgrade`);
otherwise `409 offer_policy_violation`, unless an owner/admin sends `"override": true` (audited as
`campushire.offer.policy_overridden`). `201` with `Location` and `ETag`:

```json
{"id": "uuid", "version": 1, "applicationId": "uuid", "studentId": "uuid", "companyId": "uuid", "driveId": "uuid",
 "departmentId": "uuid", "ctc": 1200000.00, "currency": "INR", "offerType": "full_time",
 "joiningDate": "2027-07-01", "respondBy": "2026-11-10", "offerLetterFileId": "uuid", "offerStatus": "extended",
 "applicationStage": "offered", "placementStatus": "placed", "policyOverridden": false}
```

Errors: `400 validation_failed`, `404` (application), `409 transition_not_allowed` (application not yet shortlisted),
`409 unique_violation` (the application has an offer), `409 offer_policy_violation`.

### `POST .../campushire/offers/{offerId}/accept` and `/decline`

The student the offer belongs to (others `404`). Only `extended` offers, until `respondBy` inclusive (institute time
zone, `campushire.time-zone`). Accepting marks an `unplaced` student `placed` and never declines other offers.
Declining the student's last active offer returns a `placed` student to `unplaced`. `200`: offer as above.
Errors: `409 offer_not_pending`, `409 offer_expired`, `412 version_conflict` (concurrent change).

## Dashboard

### `GET /api/v1/orgs/{orgId}/campushire/dashboard`

Shape depends on `role`. Staff dashboards are cached for 60 s (Redis, when available); figures come from projections
(about a second behind) and placement percentages from statistics refreshed every 5 minutes.

Placement officers and admins (`role`: `tpo` or `admin`); coordinators (`role: "coordinator"`) get the same shape
restricted to `departmentIds` (students, applications, offers, placement) and `followUps: null`:

```json
{
  "role": "tpo",
  "departmentIds": null,
  "counts": {"students": 1180, "openDrives": 3, "applicationsThisWeek": 420, "activeOffers": 96},
  "companiesByStage": {"lead": 40, "contacted": 12, "confirmed": 8, "visited": 15, "inactive": 3},
  "placement": {"totalStudents": 1180, "participatingStudents": 1100, "placedStudents": 88,
                "placementPercentage": 8.00, "offers": 96, "highestCtc": 4400000.00, "averageCtc": 980000.00,
                "refreshedAt": "2026-10-20T10:15:00Z"},
  "upcomingDrives": [{"id": "uuid", "title": "Acme SDE 2027", "companyId": "uuid", "companyName": "Acme",
                      "driveStatus": "published", "driveDate": "2026-11-02",
                      "registrationClosesAt": "2026-10-25T12:30:00Z"}],
  "followUps": {"dueToday": 2, "overdue": 1,
                "items": [{"id": "uuid", "title": "Call HR", "companyId": "uuid", "dueDate": "2026-10-18",
                           "overdue": true}]},
  "generatedAt": "2026-10-20T10:16:02Z"
}
```

`placementPercentage` is placed / participating (not opted out or higher studies), `null` without participants.
`upcomingDrives`: next 5 published, closed or running drives by drive date. `followUps`: open follow-ups due today or
earlier (first 10 by due date).

Students:

```json
{
  "role": "student",
  "student": {"id": "uuid", "rollNumber": "CS22B001", "fullName": "Asha R", "placementStatus": "unplaced",
              "profileVerified": false},
  "profile": {"completeness": 62, "missingFields": ["linkedin_url", "photo", "resume"]},
  "eligibleOpenDrives": 2,
  "applicationsByStage": {"applied": 3, "shortlisted": 1},
  "offers": [{"id": "uuid", "companyId": "uuid", "companyName": "Acme", "ctc": 1200000.00, "offerType": "full_time",
              "offerStatus": "extended", "respondBy": "2026-11-10"}],
  "generatedAt": "2026-10-20T10:16:02Z"
}
```

`profile.completeness` covers the optional fields the student maintains (not required, not hidden from or read-only
for the student role: phone, gender, date of birth, skills, links, resume, photo, plus fields the institute adds).

Recruiters:

```json
{
  "role": "recruiter",
  "companyIds": ["uuid"],
  "drives": [{"id": "uuid", "title": "Acme SDE 2027", "companyId": "uuid", "driveStatus": "in_progress",
              "driveDate": "2026-11-02", "registrationClosesAt": "2026-10-25T12:30:00Z", "applicants": 120,
              "applicantsByStage": {"applied": 80, "shortlisted": 30, "rejected": 10}}],
  "generatedAt": "2026-10-20T10:16:02Z"
}
```

Members without a CampusHire role: `{"role": null, "generatedAt": "..."}`.

## Maintenance

### `POST /api/v1/orgs/{orgId}/campushire/projections/rebuild`

Owners and admins. Rebuilds the student, drive, application and offer projections from the records (page by page),
marks rows of vanished records deleted and refreshes the placement statistics. `200`:

```json
{"projected": {"student": 1180, "drive": 25, "application": 4200, "offer": 96},
 "removed": {"student": 0, "drive": 1, "application": 0, "offer": 0},
 "statsRefreshed": true}
```

`statsRefreshed: false` when another replica was refreshing at that moment.

## Inviting recruiters and coordinators

The platform invitation endpoint creates the single-use link (7 days) and e-mails it; the response is the only place
the link is returned, so admins can also share it manually:

```http
POST /api/v1/orgs/{orgId}/invitations
{"email": "hr@acme.com", "membershipRole": "MEMBER", "roleKey": "recruiter", "attributes": {"company": "<companyId>"}}

POST /api/v1/orgs/{orgId}/invitations
{"email": "hod.cse@iiitt.ac.in", "membershipRole": "MEMBER", "roleKey": "coordinator",
 "attributes": {"department": "<departmentId>"}}
```

`201`: `{id, email, membershipRole, roleKey, attributes, status: "PENDING", expiresAt, createdAt, createdBy,
acceptUrl}`. The CampusHire presets `POST .../campushire/companies/{companyId}/recruiters/invite` and
`POST .../campushire/departments/{departmentId}/coordinators/invite` (`{"email"}`, same response, validating that the
company or department exists) need an invitation API for application code in the platform and are not available yet.

## Notifications

Sent through the platform notifications (in-app list and stream `GET /api/v1/notifications`, plus e-mail) with links
to the web app's record pages:

| `type` | Recipients | `link` | `data` |
|--------|------------|--------|--------|
| `campushire.drive.published` | eligible students with a linked account | `/app/{orgId}/m/drive/{driveId}` | `driveId` |
| `campushire.drive.cancelled` | applicants | `/app/{orgId}/m/drive/{driveId}` | `driveId` |
| `campushire.application.stage_changed` | the applicant (not when they acted themselves) | `/app/{orgId}/m/application/{id}` | `applicationId`, `from`, `to` |
| `campushire.offer.extended` | the student | `/app/{orgId}/m/offer/{offerId}` | `offerId` |
| `campushire.interview.scheduled` | the student of a new interview record | `/app/{orgId}/m/interview/{id}` | `interviewId` |
