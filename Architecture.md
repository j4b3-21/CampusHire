# CampusHire Backend Architecture

## Overview

CampusHire is a modular, metadata-driven Training & Placement ERP built using Spring Boot, PostgreSQL, and Keycloak.

The project follows a **modular monolith** architecture. Each module is self-contained with its own controllers, services, repositories, entities, DTOs, validations, and business logic. This architecture allows modules to be extracted into microservices in the future if needed.

```
src/main/java/com/campushire/campushire
│
├── config
├── security
├── common
├── exception
├── utils
├── modules
└── CampushireApplication.java
```

---

# Module Structure

Each module follows the same structure.

```
module
├── config
├── controller
├── dto
│   ├── request
│   └── response
├── entity
├── enums
├── exception
├── mapper
├── repository
├── service
│   └── impl
├── specification
├── validation
└── utils
```
# Development Order

Follow this order while implementing the backend modules. Each module depends on the ones above it, minimizing refactoring and ensuring a stable foundation.

```text
Authentication (Completed)
        │
        ▼
Users
        │
        ▼
Roles
        │
        ▼
Organization
        │
        ▼
Departments
        │
        ▼
Academics
        │
        ▼
Students
        │
        ├──────────────┐
        ▼              ▼
Documents       Companies
        │              │
        ▼              ▼
Resumes      Contacts → Recruiters
        │              │
        └──────┬───────┘
               ▼
             Jobs
               │
               ▼
          Eligibility
               │
               ▼
             Drives
               │
               ▼
          Applications
               │
      ┌────────┴────────┐
      ▼                 ▼
 Interviews         Offers
      │                 │
      └────────┬────────┘
               ▼
        Notifications
               │
               ▼
Calendar → Events → Training → Assessments
               │
               ▼
Workflows → Integrations → Reports → Analytics → Dashboard
```

## Development Milestones

### Phase 0 – Foundation
- ✅ Authentication
- ✅ Keycloak
- ✅ JWT
- ✅ Spring Security
- ✅ Flyway
- ✅ PostgreSQL

### Phase 1 – Core Platform
1. Users
2. Roles
3. Organizations
4. Departments
5. Academics

### Phase 2 – Student Management
6. Students
7. Documents
8. Resumes

### Phase 3 – Employer CRM
9. Companies
10. Contacts
11. Recruiters

### Phase 4 – Placement Lifecycle
12. Jobs
13. Eligibility
14. Drives
15. Applications
16. Interviews
17. Offers
18. Internships

### Phase 5 – Communication
19. Notifications
20. Emails
21. Calendar
22. Events

### Phase 6 – Training
23. Training
24. Assessments

### Phase 7 – Automation
25. Workflows
26. Integrations

### Phase 8 – Insights & Administration
27. Reports
28. Analytics
29. Dashboard
30. Search
31. Audit
32. Settings

> **Rule:** Never start implementing a module until all of its parent dependencies in the roadmap are complete.

---

# Modules

---

## Auth

Responsible for authentication and authorization.

Features

- Login
- Logout
- Refresh Token
- OAuth Login
- Keycloak Integration
- JWT
- User Registration
- Session Management

---

## Users

Stores internal user information linked to Keycloak.

Responsibilities

- User Profile
- User Preferences
- Last Login
- Avatar
- Status
- Institute Association

---

## Roles

Role Based Access Control (RBAC).

Responsibilities

- Roles
- Permissions
- Module Access
- Resource Access
- Custom Permissions

---

## Organizations

Represents an institute.

Responsibilities

- Institute Information
- Branding
- Campus Configuration
- Global Settings

Future support for multiple institutes.

---

## Departments

Academic departments.

Examples

- CSE
- ECE
- Mechanical

Responsibilities

- Department Details
- Coordinators
- Students
- Placement Statistics

---

## Academics

Academic hierarchy.

Responsibilities

- Programs
- Branches
- Semester
- Batch
- Section
- CGPA
- Credits
- Backlogs

---

## Students

Primary module.

Stores complete placement profile.

Responsibilities

- Personal Details
- Academic Details
- Resume
- Skills
- Projects
- Certifications
- Achievements
- Placement Eligibility
- Placement History

---

## Companies

Company CRM.

Responsibilities

- Company Profile
- Industry
- Website
- Hiring History
- Offers
- Company Notes

---

## Contacts

Company contacts.

Responsibilities

- HR
- Recruiters
- Hiring Managers
- Phone
- Email
- Notes

---

## Recruiters

Recruiter profiles.

Responsibilities

- Assigned Companies
- Meetings
- Communication
- Visit History

---

## Jobs

Job postings.

Responsibilities

- Job Description
- CTC
- Skills
- Eligibility
- Location
- Hiring Process

---

## Drives

Campus placement drives.

Responsibilities

- Drive Scheduling
- Registration
- Timeline
- Venue
- Drive Status

---

## Eligibility

Eligibility engine.

Responsibilities

- CGPA Rules
- Backlog Rules
- Department Rules
- Batch Rules
- Dynamic Filters

---

## Applications

Tracks student applications.

Lifecycle

```
Applied
↓
Shortlisted
↓
Assessment
↓
Interview
↓
Offer
↓
Joined
```

---

## Interviews

Interview management.

Responsibilities

- Interview Slots
- Interviewers
- Feedback
- Results

---

## Offers

Offer management.

Responsibilities

- Offer Letter
- Salary
- Joining Date
- Acceptance
- Status

---

## Internships

Internship management.

Responsibilities

- Summer Internship
- Winter Internship
- PPO
- Research Internship

---

## Resumes

Resume management.

Responsibilities

- Resume Builder
- Resume Upload
- Resume Versioning
- ATS Score
- Resume Templates

---

## Documents

Document management.

Responsibilities

- Resume
- Certificates
- Offer Letters
- Identity Proof
- Photos
- Verification

---

## Training

Training activities.

Responsibilities

- Courses
- Workshops
- Attendance
- Progress
- Certifications

---

## Assessments

Assessment platform.

Responsibilities

- Coding Tests
- Aptitude
- MCQs
- Communication Tests
- Results

---

## Events

Placement events.

Responsibilities

- PPTs
- Workshops
- Seminars
- Hackathons

---

## Calendar

Calendar management.

Responsibilities

- Placement Calendar
- Interview Calendar
- Training Calendar
- Google Calendar Sync
- ICS Export

---

## Notifications

Notification service.

Responsibilities

- In-App Notifications
- Push Notifications
- Email Notifications
- SMS Notifications

---

## Emails

Email communication.

Responsibilities

- Email Templates
- Bulk Mail
- Mail Scheduling
- Tracking

---

## CRM

Placement CRM.

Responsibilities

- Company Leads
- Meetings
- Tasks
- Follow-ups
- Pipelines

---

## Analytics

Analytics engine.

Responsibilities

- Placement Trends
- Department Reports
- Hiring Statistics
- KPIs
- Dashboards

---

## Reports

Report generation.

Responsibilities

- Excel
- PDF
- CSV
- Scheduled Reports

---

## Workflows

Automation engine.

Responsibilities

- Approval Workflow
- Email Automation
- Reminder Automation
- Event Automation

---

## Integrations

External integrations.

Responsibilities

- Keycloak
- Google Workspace
- ERP
- LMS
- Zoom
- Microsoft Teams
- Email Gateway
- SMS Gateway

---

## Search

Global search.

Responsibilities

- Full-text Search
- Filters
- Saved Searches
- Quick Search

---

## Settings

System configuration.

Responsibilities

- Institute Settings
- Theme
- Email Settings
- Placement Policies
- Feature Flags

---

## Audit

Audit logging.

Responsibilities

- Login Logs
- Activity Logs
- Data Changes
- Security Events

---

## Dashboard

Aggregated dashboard data.

Responsibilities

- Student Dashboard
- Recruiter Dashboard
- Placement Officer Dashboard
- Analytics Widgets

---

# Common Components

## Config

Application configuration.

- Jackson
- CORS
- OpenAPI
- Cache
- Scheduling

---

## Security

Security configuration.

- Keycloak
- JWT
- Authentication
- Authorization

---

## Common

Reusable components.

Examples

- BaseEntity
- ApiResponse
- Constants
- Pagination
- Utilities

---

## Exception

Global exception handling.

- GlobalExceptionHandler
- Custom Exceptions
- Validation Exceptions

---

## Utils

Helper classes.

Examples

- Excel Utilities
- File Utilities
- Date Utilities
- JSON Utilities
- Validation Utilities

---

# Technology Stack

Backend

- Java 25
- Spring Boot
- Spring Security
- Spring Data JPA
- PostgreSQL
- Flyway
- Hibernate
- Apache POI
- Keycloak

---

# Architectural Principles

- Modular Monolith
- Domain-Driven Module Separation
- REST-first API Design
- DTO-based Communication
- Service Layer Pattern
- Repository Pattern
- Specification Pattern
- Builder Pattern
- Validation at API Boundary
- Stateless Authentication
- Metadata-driven Design
- Future Microservice Ready

---

# Future Modules

Potential additions

- Alumni Portal
- Mobile API
- AI Resume Review
- AI Interview Assistant
- Placement Prediction
- Resume Parser
- OCR
- Chatbot
- Video Interview
- Company Portal
- Student Mobile App