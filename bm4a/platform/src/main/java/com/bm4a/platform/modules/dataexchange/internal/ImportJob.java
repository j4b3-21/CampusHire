package com.bm4a.platform.modules.dataexchange.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * An import job row.
 *
 * @param mapping {@code column header -> field key} of every imported column
 * @param errorSample the first failed rows, {@code [{row, column, field, code, message}]}
 */
public record ImportJob(UUID id, UUID organizationId, UUID moduleId, String moduleKey, UUID fileId, String fileName,
        SpreadsheetFormat format, ImportMode mode, String matchField, Map<String, String> mapping, boolean dryRun,
        ImportStatus status, Integer totalRows, int processedRows, int createdRows, int updatedRows, int failedRows,
        UUID errorFileId, JsonNode errorSample, String errorMessage, UUID createdBy, Instant createdAt,
        Instant startedAt, Instant finishedAt, Instant heartbeatAt) {

    public ImportJob {
        mapping = Map.copyOf(mapping);
    }
}
