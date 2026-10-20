package com.campushire.projections.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordEvent;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.campushire.common.CampusModules;
import com.campushire.common.RecordValues;
import com.campushire.projections.internal.ProjectionRepository.ApplicationRow;
import com.campushire.projections.internal.ProjectionRepository.DriveRow;
import com.campushire.projections.internal.ProjectionRepository.OfferRow;
import com.campushire.projections.internal.ProjectionRepository.StudentRow;
import com.campushire.projections.internal.ProjectionRepository.Table;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps records to projection rows. Always projects the record's <em>current</em> state, whatever event triggered it,
 * so out-of-order or repeated events converge on the latest version.
 */
@Component
public class Projector {

    /** Projected values beyond the column precision are dropped rather than failing the listener forever. */
    private static final BigDecimal MAX_GRADE = new BigDecimal("99.99");
    private static final BigDecimal MAX_PERCENTAGE = new BigDecimal("999.99");

    private final ModuleSchemas schemas;
    private final RecordReader reader;
    private final ProjectionRepository repository;
    private final Clock clock;

    Projector(ModuleSchemas schemas, RecordReader reader, ProjectionRepository repository, Clock clock) {
        this.schemas = schemas;
        this.reader = reader;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void handle(RecordEvent event) {
        Optional<String> moduleKey = schemas.findById(event.organizationId(), event.moduleId())
                .map(ModuleSchema::apiKey);
        if (moduleKey.isEmpty()) {
            return;
        }
        String key = moduleKey.get();
        if (CampusModules.JOB.equals(key)) {
            if (!(event instanceof RecordEvent.Deleted)) {
                reader.findActive(event.organizationId(), event.moduleId(), event.recordId()).ifPresent(job ->
                        repository.updateJobCtc(job.organizationId(), job.id(), RecordValues.amount(job.data(), "ctc")));
            }
            return;
        }
        Table table = table(key);
        if (table == null) {
            return;
        }
        Optional<RecordView> current = event instanceof RecordEvent.Deleted ? Optional.empty()
                : reader.findActive(event.organizationId(), event.moduleId(), event.recordId());
        if (current.isPresent()) {
            apply(key, current.get(), jobId -> jobCtc(event.organizationId(), jobId));
        } else {
            repository.markDeleted(table, event.organizationId(), event.recordId(), event.version(),
                    SqlValues.now(clock));
        }
    }

    /** Projects one active record of a projected module; other modules are ignored. */
    @Transactional
    public void apply(String moduleKey, RecordView record, Function<UUID, BigDecimal> jobCtcs) {
        ObjectNode data = record.data();
        switch (moduleKey) {
            case CampusModules.STUDENT -> repository.upsertStudent(new StudentRow(record.id(),
                    record.organizationId(), record.ownerUserId(), truncate(text(data, "roll_number"), 64),
                    truncate(text(data, "full_name"), 255), truncate(RecordValues.text(data, "email"), 254),
                    RecordValues.uuid(data, "department"), RecordValues.uuid(data, "batch"),
                    truncate(RecordValues.text(data, "program"), 32),
                    bounded(RecordValues.decimal(data, "cgpa"), MAX_GRADE),
                    count(RecordValues.integer(data, "active_backlogs")),
                    count(RecordValues.integer(data, "total_backlogs")),
                    bounded(RecordValues.decimal(data, "tenth_percentage"), MAX_PERCENTAGE),
                    bounded(RecordValues.decimal(data, "twelfth_percentage"), MAX_PERCENTAGE),
                    truncate(orDefault(RecordValues.text(data, "placement_status"), "unplaced"), 32),
                    RecordValues.bool(data, "profile_verified"), RecordValues.text(data, "skills"),
                    truncate(RecordValues.fileId(data, "resume"), 200),
                    truncate(RecordValues.fileName(data, "resume"), 255), record.version(), record.updatedAt()));
            case CampusModules.DRIVE -> {
                UUID jobId = RecordValues.uuid(data, "job");
                JsonNode eligibility = data.get("eligibility");
                repository.upsertDrive(new DriveRow(record.id(), record.organizationId(),
                        RecordValues.uuid(data, "company"), jobId, truncate(text(data, "title"), 255),
                        truncate(orDefault(RecordValues.text(data, "drive_status"), "draft"), 32),
                        RecordValues.instant(data, "registration_opens_at"),
                        RecordValues.instant(data, "registration_closes_at"), RecordValues.date(data, "drive_date"),
                        eligibility != null && eligibility.isObject() ? eligibility.toString() : "{}",
                        jobId == null ? null : jobCtcs.apply(jobId), record.version(), record.updatedAt()));
            }
            case CampusModules.APPLICATION -> {
                UUID drive = RecordValues.uuid(data, "drive");
                UUID student = RecordValues.uuid(data, "student");
                if (drive != null && student != null) {
                    repository.upsertApplication(new ApplicationRow(record.id(), record.organizationId(), drive,
                            student, RecordValues.uuid(data, "company"), RecordValues.uuid(data, "department"),
                            truncate(orDefault(RecordValues.text(data, "stage"), "applied"), 32), record.version(),
                            record.createdAt(), record.updatedAt()));
                }
            }
            case CampusModules.OFFER -> {
                UUID student = RecordValues.uuid(data, "student");
                if (student != null) {
                    repository.upsertOffer(new OfferRow(record.id(), record.organizationId(),
                            RecordValues.uuid(data, "application"), student, RecordValues.uuid(data, "company"),
                            RecordValues.uuid(data, "drive"), RecordValues.uuid(data, "department"),
                            RecordValues.amount(data, "ctc"), truncate(RecordValues.text(data, "offer_type"), 32),
                            truncate(orDefault(RecordValues.text(data, "offer_status"), "extended"), 32),
                            RecordValues.date(data, "respond_by"), record.version(), record.createdAt(),
                            record.updatedAt()));
                }
            }
            default -> {
            }
        }
    }

    static Table table(String moduleKey) {
        return switch (moduleKey) {
            case CampusModules.STUDENT -> Table.STUDENT;
            case CampusModules.DRIVE -> Table.DRIVE;
            case CampusModules.APPLICATION -> Table.APPLICATION;
            case CampusModules.OFFER -> Table.OFFER;
            default -> null;
        };
    }

    /** The CTC amount of an active job record. */
    BigDecimal jobCtc(UUID organizationId, UUID jobId) {
        return reader.findActive(organizationId, List.of(jobId)).stream().findFirst()
                .map(job -> RecordValues.amount(job.data(), "ctc"))
                .orElse(null);
    }

    private static String text(ObjectNode data, String key) {
        return orDefault(RecordValues.text(data, key), "");
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static int count(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static BigDecimal bounded(BigDecimal value, BigDecimal max) {
        return value == null || value.abs().compareTo(max) > 0 ? null : value;
    }

    static List<String> projectedModules() {
        return List.of(CampusModules.STUDENT, CampusModules.DRIVE, CampusModules.APPLICATION, CampusModules.OFFER);
    }
}
