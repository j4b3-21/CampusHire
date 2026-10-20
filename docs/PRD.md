# CampusHire - Product Requirements and Work Breakdown

Version 1.0 - 2026-10-20 - Owner: Bhuvan M Acharya

## 1. Problem and goals

The Training & Placement cell runs placements on spreadsheets, email threads and WhatsApp groups. Student data is
re-typed for every drive, eligibility is checked by hand, students miss deadlines, recruiters email shortlists as
attachments and nobody can answer "what is our placement percentage per department right now?".

**Goals for V1 (one academic placement season at one college):**

1. One source of truth for students, companies, drives, applications and offers.
2. Spreadsheets in and out everywhere (bulk import with error reports, export of any list).
3. Drive lifecycle with automatic eligibility, one-click apply and a transparent status pipeline.
4. Role-appropriate access for TPO, coordinators, students and recruiters.
5. Live placement analytics and downloadable reports.
6. Runs on one college server for 10,000 users (ADR-006), installable in minutes.

**Non-goals for V1:** online assessments/proctoring, resume builder/ATS scoring, mobile apps, payments,
multi-college SaaS billing (the platform is multi-tenant, but onboarding of other colleges is manual).

## 2. Personas and roles

| Role | Membership | Org role key | Access summary |
|------|------------|--------------|----------------|
| TPO / Placement Officer | ADMIN (first user is OWNER) | `tpo` | Everything, including settings, metadata builder, imports, audit |
| Department coordinator | MEMBER | `coordinator` | Students and applications of their department (attribute `department`), read companies/jobs/drives, verify student profiles |
| Student | MEMBER | `student` | Own profile (academic fields read-only), published drives, own applications and offers |
| Recruiter | MEMBER | `recruiter` | Own company (attribute `company`), its jobs/drives/applicants; shortlist and update statuses; no access to other companies |

## 3. Milestones

| Milestone | Scope | Exit criteria |
|-----------|-------|---------------|
| M0 Foundation | Monorepo, BM4A subtree, build, CI, docs | `./mvnw verify` green, ADRs merged |
| M1 Platform extensions | Roles/scopes/field security, invitations, files, Excel/CSV import-export, workflow, notifications, schema cache | Platform integration tests green; ADR-004/005/007 implemented |
| M2 CampusHire domain | App pack, onboarding, eligibility, drives, applications pipeline, offers, CRM, analytics, reports | Backend integration tests for every story below |
| M3 Web app | Design system, metadata-driven tables/forms, role dashboards, import wizard, pipeline board, admin builder | Every story usable end to end in the browser |
| M4 Production | Compose prod stack, Nginx, Jenkins, backups, monitoring, load test, security review | k6 targets met (ADR-006), security findings fixed |

## 4. User stories and acceptance criteria

### TPO

- **T1 Set up the institute.** As the TPO I sign in, create the institute and install CampusHire.
  - AC: `POST /orgs` then `POST /orgs/{id}/campushire/setup` creates all app-pack modules, relationships, roles and
    the application workflow; running setup again changes nothing and returns the installed pack version.
  - AC: settings store allowed student email domains (e.g. `iiitt.ac.in`) and placement policy.
- **T2 Import students from Excel.** Download a template, upload the academic section's sheet, map columns, dry
  run, fix errors, commit.
  - AC: 5,000-row XLSX imports in under 60 s; every failed row appears in the downloadable error file with the
    reason; `UPSERT` on `roll_number` updates existing students without duplicates; department `CSE` resolves to
    the department record.
- **T3 Manage companies (CRM).** Track companies through `lead -> contacted -> confirmed -> visited`, contacts and
  follow-ups with due dates.
  - AC: follow-ups due today appear on the TPO dashboard; company list filterable by stage and tier; exportable.
- **T4 Create and publish a drive** with eligibility (min CGPA, max active backlogs, departments, batches,
  programs, min 10th/12th %, exclude placed students / dream-company upgrade policy) and a registration window.
  - AC: before publishing, the TPO sees the number of eligible students and a sample; publishing notifies every
    eligible student in-app and by email; drafts are invisible to students.
- **T5 Run the pipeline.** Move applicants through `applied -> shortlisted -> assessment -> interview -> offered ->
  joined` (or `rejected` / `withdrawn`) individually, in bulk, or by uploading a shortlist (roll numbers) from
  Excel.
  - AC: invalid transitions are rejected; every change is in the application history with actor and comment;
    affected students are notified.
- **T6 Record offers.** Create an offer for an application; the student's placement status and offer count update;
  the policy (max offers, dream threshold) is enforced on later applications.
- **T7 Analytics and reports.** Dashboard with placement %, offers, median/average/highest CTC, per department,
  per company, monthly trend, funnel per drive; download placement report as Excel and PDF.
  - AC: numbers equal a manual count over the seeded data set; dashboard loads in < 1 s with 10k students.
- **T8 Invite staff and recruiters.** Invite coordinators (with department) and recruiters (with company) by email.
  - AC: the invitation can only be accepted by a signed-in user with the same verified email; it expires after 7
    days and works once.
- **T9 Customize.** Add a field (e.g. `gap_years`) to Student, place it on the layout, and see it in forms, tables,
  imports and exports without a deployment. Edit workflow transitions and role permissions.

### Coordinator

- **C1** See and filter only my department's students; verify profiles (`profile_verified`).
  - AC: requests for other departments' students return 404; exports contain only my department.
- **C2** Track my department's applications and placement statistics.

### Student

- **S1 Sign in with college Google account** and get linked to my student record automatically (email match).
  - AC: a Google account outside the allowed domains gets no access; a domain account without a matching student
    record sees "contact the placement cell".
- **S2 Complete my profile**: phone, skills, links, resume (PDF), photo. CGPA, backlogs, roll number and placement
  status are read-only for me.
- **S3 See drives I can apply to** with clear reasons when I am not eligible ("CGPA 6.8 < 7.0").
- **S4 Apply in one click** inside the registration window; applying twice returns the same application; I can
  withdraw before shortlisting.
- **S5 Track my applications** and get notified of every status change and interview schedule.
- **S6 Accept or decline an offer** before its deadline.

### Recruiter

- **R1** Accept the invitation, see my company's drives and their applicants (no phone/DOB), download resumes.
- **R2** Shortlist / reject applicants in bulk or by uploading roll numbers; schedule interviews; record results.
  - AC: any attempt to read another company's drives or applicants returns 404.

## 5. Edge cases and risks

| Case | Handling |
|------|----------|
| Double-click / retry on apply | `UNIQUE (drive_id, student_id)` + `ON CONFLICT DO NOTHING`; returns existing application |
| Registration closes during apply | Window checked inside the transaction using server time (UTC) |
| CGPA updated after applying | Eligibility re-checked at shortlist; TPO sees a warning badge, no silent removal |
| Student already placed | Policy: blocked unless drive CTC >= dream threshold and upgrades allowed |
| Duplicate roll numbers / emails in an import | Unique fields reject the second row with a clear error |
| Partial import failure | Batches commit independently; error file lists failed rows; UPSERT makes re-runs safe |
| Formula injection in exports | Cells prefixed with `'` when starting with `= + - @` |
| Recruiter data leakage | Record scopes by company attribute + hidden fields + tests for cross-company access |
| Google Workspace outage | Password login (Keycloak) remains available for staff and recruiters |
| SMTP outage / Gmail sending limits | Email outbox with retries and backoff; in-app notifications unaffected; batch size configurable |
| OpenFGA unavailable | Fail closed (503) per ADR-002; health check and restart policy |
| Large uploads | 25 MB limit at Nginx and Spring; MIME sniffing; per-module import concurrency 1 |

## 6. App pack data model (V1)

All modules are created in `ROLES` access mode (ADR-004). Field options listed as `value` (labels are title case).

| Module | Fields (type; flags) |
|--------|----------------------|
| `department` | `name` (TEXT; req, searchable, sortable), `code` (TEXT; req, unique, filterable), `hod_name` (TEXT), `coordinator_email` (EMAIL) |
| `batch` | `name` (TEXT; req, unique, searchable), `program` (SINGLE_SELECT `btech, mtech, mba, msc, phd`; filterable), `graduation_year` (INTEGER; req, filterable, sortable) |
| `student` | `roll_number` (TEXT; req, unique, searchable, filterable, sortable), `full_name` (TEXT; req, searchable, sortable), `email` (EMAIL; req, unique, searchable, filterable), `phone` (PHONE; sensitive), `gender` (SINGLE_SELECT `male, female, other`; filterable), `date_of_birth` (DATE; sensitive), `department` (LOOKUP department; req, filterable), `batch` (LOOKUP batch; req, filterable), `program` (SINGLE_SELECT as batch; filterable), `cgpa` (DECIMAL scale 2, 0-10; filterable, sortable), `active_backlogs` (INTEGER min 0; filterable, sortable), `total_backlogs` (INTEGER min 0), `tenth_percentage` (DECIMAL scale 2, 0-100; filterable), `twelfth_percentage` (DECIMAL scale 2, 0-100; filterable), `skills` (LONG_TEXT; searchable), `linkedin_url` (URL), `github_url` (URL), `resume` (FILE_REFERENCE), `photo` (IMAGE_REFERENCE), `placement_status` (SINGLE_SELECT `unplaced, placed, opted_out, higher_studies`; filterable; default `unplaced`), `profile_verified` (BOOLEAN; filterable; default false) |
| `company` | `name` (TEXT; req, unique, searchable, sortable), `industry` (SINGLE_SELECT `it_services, product, finance, consulting, core_engineering, analytics, startup, government, other`; filterable), `tier` (SINGLE_SELECT `dream, super_dream, regular, mass`; filterable), `pipeline_stage` (SINGLE_SELECT `lead, contacted, confirmed, visited, inactive`; filterable; default `lead`), `website` (URL), `headquarters` (TEXT), `next_follow_up` (DATE; filterable, sortable), `notes` (LONG_TEXT) |
| `contact` | `full_name` (TEXT; req, searchable), `company` (LOOKUP company; req, filterable), `designation` (TEXT), `email` (EMAIL; searchable), `phone` (PHONE), `is_primary` (BOOLEAN), `notes` (LONG_TEXT) |
| `follow_up` | `title` (TEXT; req, searchable), `company` (LOOKUP company; req, filterable), `contact` (LOOKUP contact), `type` (SINGLE_SELECT `call, email, meeting, visit`), `due_date` (DATE; req, filterable, sortable), `status` (SINGLE_SELECT `open, done`; filterable; default `open`), `notes` (LONG_TEXT) |
| `job` | `title` (TEXT; req, searchable, sortable), `company` (LOOKUP company; req, filterable), `job_type` (SINGLE_SELECT `full_time, internship, internship_ppo`; filterable), `ctc` (CURRENCY INR; filterable, sortable), `stipend` (CURRENCY INR), `location` (TEXT; searchable), `description` (LONG_TEXT), `skills` (LONG_TEXT; searchable) |
| `drive` | `title` (TEXT; req, searchable, sortable), `company` (LOOKUP company; req, filterable), `job` (LOOKUP job; req, filterable), `status` (SINGLE_SELECT `draft, published, registration_closed, in_progress, completed, cancelled`; filterable; default `draft`), `registration_opens_at` (DATETIME; sortable), `registration_closes_at` (DATETIME; req, filterable, sortable), `drive_date` (DATE; filterable, sortable), `mode` (SINGLE_SELECT `on_campus, virtual, off_campus`), `venue` (TEXT), `eligibility` (JSON), `description` (LONG_TEXT) |
| `application` | `drive` (LOOKUP drive; req, filterable), `student` (LOOKUP student; req, filterable), `company` (LOOKUP company; req, filterable), `department` (LOOKUP department; filterable), `status` (SINGLE_SELECT workflow states; filterable, sortable; default `applied`), `current_round` (TEXT), `remarks` (LONG_TEXT) |
| `interview` | `application` (LOOKUP application; req, filterable), `drive` (LOOKUP drive; filterable), `company` (LOOKUP company; filterable), `round_name` (TEXT; req), `scheduled_at` (DATETIME; req, sortable, filterable), `mode` (SINGLE_SELECT `in_person, video, phone`), `location` (TEXT), `result` (SINGLE_SELECT `pending, passed, failed, no_show`; filterable; default `pending`), `feedback` (LONG_TEXT) |
| `offer` | `application` (LOOKUP application; req, unique, filterable), `student` (LOOKUP student; req, filterable), `company` (LOOKUP company; req, filterable), `drive` (LOOKUP drive; filterable), `department` (LOOKUP department; filterable), `ctc` (CURRENCY INR; req, sortable, filterable), `offer_type` (SINGLE_SELECT `full_time, internship, ppo`; filterable), `joining_date` (DATE), `respond_by` (DATE), `offer_letter` (FILE_REFERENCE), `status` (SINGLE_SELECT `extended, accepted, declined, revoked`; filterable; default `extended`) |

Drive `eligibility` JSON: `{"minCgpa": 7.0, "maxActiveBacklogs": 0, "departments": [ids], "batches": [ids],
"programs": ["btech"], "minTenth": 60, "minTwelfth": 60, "excludePlaced": true}`.

Organization settings (`settings.campushire`): `{"allowedEmailDomains": ["iiitt.ac.in"], "policy":
{"maxOffersPerStudent": 1, "dreamCtcThreshold": 1200000, "allowDreamUpgrade": true}, "appPackVersion": 1}`.

Application workflow: states `applied` (OPEN), `shortlisted`, `assessment`, `interview` (IN_PROGRESS), `offered`,
`joined` (WON), `rejected`, `withdrawn` (LOST). Staff (`tpo`, `coordinator`) and `recruiter` may use every forward
transition and `rejected`; only `student` may use `withdrawn` (from `applied` or `shortlisted`).

## 7. API surface (CampusHire endpoints)

All under `/api/v1/orgs/{orgId}/campushire` unless noted.

| Method and path | Who | Purpose |
|-----------------|-----|---------|
| `POST /setup` | admin | Install/upgrade app pack (idempotent) |
| `GET /me` | member | Role, linked student/company/department, capabilities for UI |
| `GET /api/v1/campushire/onboarding` / `POST /api/v1/campushire/onboarding/claim` | authenticated | Find institutes for my email domain; link me to my student record |
| `GET /drives/{id}/eligibility/preview` | staff | Eligible count + sample |
| `POST /drives/{id}/publish`, `/close`, `/complete`, `/cancel` | staff | Drive lifecycle, notifications |
| `GET /student/drives` | student | Open/upcoming drives with eligibility verdict and reasons |
| `POST /drives/{id}/apply`, `POST /drives/{id}/withdraw` | student | Idempotent apply / withdraw |
| `GET /drives/{id}/applicants` | staff, recruiter | Applicants joined with student snapshot (scoped, hidden fields applied) |
| `POST /drives/{id}/applicants/transition` | staff, recruiter | Bulk status change by application ids or roll numbers |
| `POST /drives/{id}/applicants/transition/upload` | staff, recruiter | Shortlist from Excel/CSV (roll numbers) |
| `GET /drives/{id}/applicants/export?format=xlsx|csv` | staff, recruiter | Applicant sheet |
| `POST /offers/{id}/accept`, `/decline` | student | Respond to offer |
| `POST /companies/{id}/recruiters/invite` | staff | Invite a recruiter bound to the company |
| `GET /analytics/overview`, `/departments`, `/companies`, `/ctc-distribution`, `/trend`, `/drives/{id}/funnel` | staff (coordinator: own department) | Analytics |
| `GET /reports/placement?format=xlsx|pdf` | staff | Placement report |
| `GET /dashboard` | member | Role-specific dashboard payload |
| `POST /projections/rebuild` | admin | Rebuild projections from records |
