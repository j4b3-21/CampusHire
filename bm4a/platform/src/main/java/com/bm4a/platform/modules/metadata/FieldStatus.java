package com.bm4a.platform.modules.metadata;

/**
 * Field lifecycle. Fields are never hard-deleted, so stored values are never orphaned.
 * <ul>
 * <li>{@code ACTIVE} - readable and writable</li>
 * <li>{@code DEPRECATED} - readable; existing values are kept but new or changed values are rejected</li>
 * <li>{@code ARCHIVED} - hidden from the record API and not writable; stored values are preserved, also across
 * full-replacement updates, and reappear if the field is reactivated</li>
 * </ul>
 */
public enum FieldStatus {
    ACTIVE, DEPRECATED, ARCHIVED
}
