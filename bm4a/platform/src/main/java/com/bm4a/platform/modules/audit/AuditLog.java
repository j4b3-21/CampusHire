package com.bm4a.platform.modules.audit;

/**
 * Records audit events. Joins the caller's transaction when one is active.
 */
public interface AuditLog {

    void record(AuditEntry entry);
}
