package com.bm4a.platform.modules.metadata;

import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Module setting {@code settings.accessMode} (ADR-004).
 * <ul>
 * <li>{@code MEMBERS} (default) - every member may use the module (ADR-002 behavior) unless their org role
 * configures it explicitly</li>
 * <li>{@code ROLES} - only org roles that list the module grant access to members</li>
 * </ul>
 * Organization owners and admins always have full access.
 */
public enum ModuleAccessMode {

    MEMBERS, ROLES;

    public static final String SETTING = "accessMode";

    /** The mode stored in module settings; empty when the value is present but invalid. */
    public static Optional<ModuleAccessMode> of(ObjectNode settings) {
        JsonNode value = settings == null ? null : settings.get(SETTING);
        if (value == null || value.isNull()) {
            return Optional.of(MEMBERS);
        }
        if (!value.isString()) {
            return Optional.empty();
        }
        for (ModuleAccessMode mode : values()) {
            if (mode.name().equals(value.stringValue())) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
