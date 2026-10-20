package com.bm4a.platform.modules.packs;

import java.util.List;

/**
 * What an install did.
 *
 * @param previousVersion the version installed before, {@code null} for a first install
 */
public record PackInstallReport(String packKey, int version, Status status, Integer previousVersion,
        Created created, List<Skipped> skipped) {

    public PackInstallReport {
        skipped = List.copyOf(skipped);
    }

    public enum Status {
        INSTALLED, UPGRADED, UP_TO_DATE
    }

    /**
     * Definitions added by the install.
     *
     * @param fields {@code module.field}
     * @param options {@code module.field:value} for options appended to existing select fields
     * @param layouts modules whose layout was set
     * @param workflows modules whose workflow was created
     * @param settings dotted paths of organization settings added
     */
    public record Created(List<String> modules, List<String> fields, List<String> options, List<String> layouts,
            List<String> roles, List<String> relationships, List<String> workflows, List<String> settings) {

        public Created {
            modules = List.copyOf(modules);
            fields = List.copyOf(fields);
            options = List.copyOf(options);
            layouts = List.copyOf(layouts);
            roles = List.copyOf(roles);
            relationships = List.copyOf(relationships);
            workflows = List.copyOf(workflows);
            settings = List.copyOf(settings);
        }

        public static Created none() {
            return new Created(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    /**
     * A pack entry left as it was.
     *
     * @param type {@code role}, {@code relationship}, {@code workflow}, {@code field} or {@code pack}
     * @param reason {@code exists}, {@code type_differs} or {@code changed_without_version_bump}
     */
    public record Skipped(String type, String key, String reason) {
    }
}
