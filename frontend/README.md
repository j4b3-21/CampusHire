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
| Design system | `src/components/ui/`, tokens in `src/app/globals.css` |

## Routes

| Path | Page |
|------|------|
| `/login` | Sign-in landing (Google, email, create account; all on Keycloak) |
| `/register`, `/forgot-password` | Hand off to Keycloak registration / password reset |
| `/app` | Picks the last used institute, or onboarding when the user has none |
| `/app/onboarding` | Create an institute, or guidance for students/recruiters |
| `/app/[orgId]` | Dashboard |
| `/app/[orgId]/m/[moduleKey]` | Record list (URL-synced paging, sort, search, filters, trash) |
| `/app/[orgId]/m/[moduleKey]/new` | Create form |
| `/app/[orgId]/m/[moduleKey]/[recordId]` | Detail, related records, history |
| `/app/[orgId]/m/[moduleKey]/[recordId]/edit` | Edit form (JSON Merge Patch with `If-Match`) |
| `/app/[orgId]/admin/[section]` | Admin pages (placeholders) |
| `/app/[orgId]/profile` | Profile |
