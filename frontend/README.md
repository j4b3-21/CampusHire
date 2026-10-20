# CampusHire web app

Next.js 16 (App Router, Turbopack), React 19, TypeScript, Tailwind CSS 4, Radix primitives (shadcn-style components),
TanStack Query/Table, react-hook-form + zod, keycloak-js.

## Run locally

```bash
cp .env.example .env.local   # adjust if Keycloak/API run elsewhere
npm install
npm run dev                  # http://localhost:3000
```

Needs Keycloak on `:8180` (realm `campushire`, public client `campushire-web`) and the API on `:8090`. The browser only
calls relative `/api/v1/...`; `next.config.ts` rewrites `/api/*` to `API_INTERNAL_URL`. `npm run build` produces a
`standalone` server (`.next/standalone/server.js`) for the Docker image.

`npm run lint` and `npm run build` must stay at zero errors.

## How it fits together

| Area | Where |
|------|-------|
| Auth (PKCE S256, `check-sso`, refresh before expiry, logout) | `src/lib/auth/`, `src/store/auth-store.ts`, `src/components/auth/` |
| Redirect hints (non-sensitive cookie only) | `src/proxy.ts` |
| API client (bearer, problem details, ETag/If-Match, filters) | `src/lib/api/client.ts`, `errors.ts`, `record-query.ts` |
| TanStack Query hooks | `src/lib/api/hooks/` (`useOrganizations`, `useMyAccess`, `useModules`, `useRecords`, `usePatchRecord`, …) |
| API types | `src/types/` |
| Org context, role-aware navigation | `src/components/app/org-context.tsx`, `src/lib/navigation.ts` |
| Shell (sidebar, header, ⌘K palette) | `src/components/layout/` |
| Metadata-driven records (`RecordTable`, `RecordForm`, `RecordDetail`) | `src/components/records/`, `src/lib/metadata/` |
| Personas (admin/tpo/coordinator/student/recruiter) and portal navigation | `OrgProvider` (`persona`, `isStaff`), `src/lib/navigation.ts` |
| Live notifications (fetch-based SSE client, backoff, token refresh) | `src/lib/api/sse.ts`, `src/components/notifications/` |
| Role dashboards, analytics, charts (Recharts, token colors) | `src/components/dashboard/`, `src/components/analytics/`, `src/components/charts/` |
| Drive lifecycle, eligibility editor + preview, applicants | `src/components/drives/` (mounted by `src/components/records/record-extensions.tsx`) |
| Student and recruiter portals | `src/components/student/`, `src/components/recruiter/`, `src/components/portal/` |
| Excel/CSV import wizard and exports | `src/components/import/`, `src/components/records/export-menu.tsx`, `src/lib/api/hooks/data-exchange.ts` |
| Design system | `src/components/ui/`, tokens in `src/app/globals.css` (`--chart-*` for charts) |

`GET /api/v1/notifications/stream` is served by a Route Handler (`src/app/api/v1/notifications/stream/route.ts`) that
pipes the API stream with `Cache-Control: no-transform`; through the generic `/api/*` rewrite the Next server would
gzip `text/event-stream` and hold events back. In production nginx routes that path straight to the API.

## Routes

| Path | Page |
|------|------|
| `/login` | Sign-in landing (Google, email, create account; all on Keycloak) |
| `/register`, `/forgot-password` | Hand off to Keycloak registration / password reset |
| `/app` | Picks the last used institute, or onboarding when the user has none |
| `/app/onboarding` | Students: join the institute matching their email domain (claim); otherwise create an institute |
| `/app/[orgId]` | Role dashboard (staff, student, recruiter) |
| `/app/[orgId]/analytics` | Placement analytics and report download (staff) |
| `/app/[orgId]/notifications` | All notifications |
| `/app/[orgId]/student/drives` · `/applications` · `/offers` · `/profile` | Student portal |
| `/app/[orgId]/recruiter/applicants` | Applicants per drive (recruiters; staff too) |
| `/app/[orgId]/admin/import` | Excel/CSV import wizard (`?module=` preselects; anyone with create permission) |
| `/app/[orgId]/m/[moduleKey]` | Record list (URL-synced paging, sort, search, filters, trash) |
| `/app/[orgId]/m/[moduleKey]/new` | Create form |
| `/app/[orgId]/m/[moduleKey]/[recordId]` | Detail, related records, history; drives add Eligibility/Applicants tabs and a lifecycle panel (`?tab=`) |
| `/app/[orgId]/m/[moduleKey]/[recordId]/edit` | Edit form (JSON Merge Patch with `If-Match`) |
| `/app/[orgId]/admin/[section]` | Admin pages (members, roles, workflows, modules, settings; audit log is a placeholder) |
| `/app/[orgId]/profile` | Profile |
