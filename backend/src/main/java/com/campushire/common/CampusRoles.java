package com.campushire.common;

/** Org role keys of the app pack and the membership attributes their record scopes use. */
public final class CampusRoles {

    public static final String TPO = "tpo";
    public static final String COORDINATOR = "coordinator";
    public static final String STUDENT = "student";
    public static final String RECRUITER = "recruiter";

    /** Student record ID of a student member. */
    public static final String ATTR_STUDENT = "student";
    /** Department record ID(s) of a coordinator, and of a student (kept in sync with the student record). */
    public static final String ATTR_DEPARTMENT = "department";
    /** Batch record ID of a student (kept in sync with the student record). */
    public static final String ATTR_BATCH = "batch";
    /** Company record ID(s) of a recruiter. */
    public static final String ATTR_COMPANY = "company";

    private CampusRoles() {
    }
}
