# BM4A (Base Model For All)
## Development Roadmap & Implementation Phases

**Version:** 1.1
**Author:** Bhuvan M Acharya

---

# Implementation Status (updated 2026-10-20, branch `feature/platform-foundation`)

Legend: **Done** = implemented, tested and documented; **Partial** = foundation in place, scope noted;
**Deferred** = designed or planned, not implemented.

| Phase / capability | Status | Notes |
|--------------------|--------|-------|
| 0 - Organizations (create, settings, lifecycle, isolation) | Done | Archive by owner; suspend/reactivate by platform admin |
| 0 - Subscription management | Deferred | Needs billing model |
| 0 - Users: profiles, preferences | Done | `(issuer, subject)` identity, JIT provisioning |
| 0 - Users: invitations, teams | Partial | E-mail invitations done (ADR-004: hashed single-use tokens, verified e-mail, 7-day expiry, sent through the notifications outbox); teams deferred (OpenFGA usersets) |
| 0 - Authentication (OAuth2/OIDC, SSO, MFA, Google) | Done / Keycloak-configured | API validates issuer, audience, expiry, signature. MFA/Google/SSO are Keycloak realm settings |
| 0 - Authorization: organization permissions (OpenFGA) | Done | Owner/admin/member model, fail-closed, outbox tuple sync |
| 0 - Audit: data changes, security events | Done | Append-only (DB triggers), redaction; login tracking stays in Keycloak events |
| 1 - Module builder | Done | Tenant and platform-global modules, versions, archive/restore |
| 1 - Field builder | Done | 17 types incl. currency, select, JSON, file/image references, lookup |
| 1 - Formula fields | Deferred | No tenant expressions are evaluated; safe sandboxed language planned (ADR-001) |
| 1 - Layout builder (sections, tabs) | Done (metadata) | Conditional visibility and drag & drop UI deferred |
| 2 - Record engine (CRUD, soft delete, restore, versioning) | Done | JSONB, If-Match concurrency, history and revert, filters/sort/search |
| 3 - Relationships (1:1, 1:N, N:M, lookups) | Done | Cardinality via unique indexes; restrict/detach delete behavior |
| 4 - Permission engine: org permissions | Done | |
| 4 - Permission engine: org roles, module / record permissions, field security | Done | ADR-004: `access` module, OpenFGA `role`/`module` types, record scopes (OWN, ATTRIBUTE, `$id`, `where`) as SQL predicates, hidden/read-only fields, `/me/access`, `RecordOperations`, `RecordWriteGuard`, `MembershipAdministration` |
| 4 - PostgreSQL row-level security | Deferred | Defense in depth per ADR-002 section 4 |
| 5-8 - Workflow, automation, forms, reporting | Deferred | Extension point: `RecordEvent` published on every record change |
| Admin UI | Deferred | API-first milestone: OpenAPI/Swagger UI, request collection |
| Platform operations | Done | Docker (non-root), compose with health checks, CI, health probes, metrics, rate limiting |
| Notifications (in-app, real-time, e-mail) | Done | ADR-007: `Notifier` API, notification list/read API, SSE stream fanned out through Redis pub/sub, SMTP outbox worker with retries, Thymeleaf templates, invitation e-mail |
| Caching: module schemas | Done | ADR-007: Redis, 10-minute TTL, generation-based eviction after commit, circuit breaker with PostgreSQL fallback; role documents and app-level caches deferred |

---

# Overview

BM4A is a metadata-driven, multi-tenant enterprise application platform capable of powering:

- CampusHire
- CRM
- ERP
- HRMS
- Restaurant Management
- Inventory Management
- Hospital Management
- School ERP
- Project Management
- Custom Business Applications

The goal is:

> Build once. Configure forever.

Applications should be configurations of BM4A rather than separate software projects.

---

# Phase 0 – Foundation Platform

## Objectives

- Multi-tenancy
- Authentication
- Authorization
- Organizations
- Users
- Audit Logging

## Technology

- Spring Boot
- PostgreSQL
- Keycloak
- OpenFGA
- Redis
- Docker

## Modules

### Organization Service
- Create Organization
- Manage Settings
- Subscription Management
- Organization Isolation

### User Service
- User Profiles
- User Preferences
- Invitations
- Team Management

### Authentication (Keycloak)
- OAuth2
- OIDC
- Google Login
- MFA
- SSO

### Authorization (OpenFGA)
- Relationship Based Access Control
- Resource Permissions
- Organization Permissions

### Audit Service
- Login Tracking
- Data Changes
- Security Events

---

# Phase 1 – Metadata Engine

## Objectives

Enable creation of dynamic modules without code.

### Module Builder
Examples:

- Student
- Employee
- Customer
- Inventory
- Lead

### Field Builder

Supported Types:

- Text
- Number
- Boolean
- Date
- Currency
- Email
- Phone
- File
- Image
- JSON
- Formula
- Lookup

### Layout Builder

- Sections
- Tabs
- Conditional Visibility
- Drag & Drop UI

---

# Phase 2 – Record Engine

## Objectives

Store business data dynamically.

### Features

- Create Record
- Update Record
- Delete Record
- Soft Delete
- Restore
- Versioning

### Storage

JSONB-based records.

---

# Phase 3 – Relationship Engine

## Objectives

Connect records together.

### Relationship Types

- One-to-One
- One-to-Many
- Many-to-Many

Examples:

- Student → Department
- Employee → Manager
- Order → Customer

---

# Phase 4 – Permission Engine

## Objectives

Fine-grained authorization.

### Features

- Row-Level Security
- Module Permissions
- Record Permissions
- Organization Permissions

Powered by OpenFGA.

---

# Phase 5 – Workflow Engine

## Objectives

Business Process Management.

### Components

- State
- Transition
- Task
- Approval
- Action

Example:

Applied → Screening → Interview → Offer

---

# Phase 6 – Automation Engine

## Objectives

Event-driven automation.

### Trigger Examples

- Record Created
- Record Updated
- Workflow Changed

### Actions

- Send Email
- Create Record
- Update Record
- Call Webhook

---

# Phase 7 – Forms Engine

## Objectives

Dynamic form generation.

### Features

- Form Builder
- Conditional Logic
- Public Forms
- Internal Forms
- Multi-Step Forms

---

# Phase 8 – Reporting Engine

## Objectives

Business Intelligence.

### Features

- Reports
- Dashboards
- KPIs
- Charts
- Aggregations
- Exports

---

# Long-Term Vision

A user should be able to type:

"Create a Restaurant ERP"

and BM4A automatically generates:

- Modules
- Fields
- Relationships
- Permissions
- Workflows
- Reports
- Dashboards

without writing code.
