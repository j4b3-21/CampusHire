package com.campushire.projections.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.access.RecordScope;
import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.common.CampusModules;
import com.campushire.common.RecordValues;
import com.campushire.projections.internal.ProjectionRepository.Table;

/**
 * Rebuilds every projection of an organization from its records, page by page (each page commits on its own). Rows
 * whose record no longer exists are marked deleted. Safe to run while the listeners are active: both converge on the
 * current record versions.
 */
@Service
public class ProjectionRebuildService {

    private static final int PAGE_SIZE = PageQuery.MAX_SIZE;
    private static final String ORDER = "created_at";

    private final TenantAccess tenants;
    private final RecordOperations records;
    private final RecordReader reader;
    private final Projector projector;
    private final ProjectionRepository repository;
    private final PlacementStatsRefresher stats;
    private final Clock clock;

    ProjectionRebuildService(TenantAccess tenants, RecordOperations records, RecordReader reader, Projector projector,
            ProjectionRepository repository, PlacementStatsRefresher stats, Clock clock) {
        this.tenants = tenants;
        this.records = records;
        this.reader = reader;
        this.projector = projector;
        this.repository = repository;
        this.stats = stats;
        this.clock = clock;
    }

    /** @param projected records projected per module; {@code removed}: rows marked deleted per module */
    public record RebuildResult(Map<String, Integer> projected, Map<String, Integer> removed, boolean statsRefreshed) {
    }

    /** Requires {@code METADATA_MANAGE} (owners and admins). */
    public RebuildResult rebuild(CurrentUser user, UUID organizationId) {
        TenantContext tenant = tenants.require(user, organizationId, Permission.METADATA_MANAGE);
        Map<UUID, BigDecimal> jobCtcs = new HashMap<>();
        forEach(tenant, CampusModules.JOB, job -> jobCtcs.put(job.id(), RecordValues.amount(job.data(), "ctc")));
        Map<String, Integer> projected = new LinkedHashMap<>();
        Map<String, Integer> removed = new LinkedHashMap<>();
        for (String module : Projector.projectedModules()) {
            Set<UUID> seen = new HashSet<>();
            forEach(tenant, module, record -> {
                projector.apply(module, record, jobCtcs::get);
                seen.add(record.id());
            });
            projected.put(module, seen.size());
            removed.put(module, removeVanished(tenant, Projector.table(module), seen));
        }
        return new RebuildResult(projected, removed, stats.refresh());
    }

    private int removeVanished(TenantContext tenant, Table table, Set<UUID> seen) {
        List<UUID> candidates = new ArrayList<>(repository.activeIds(table, tenant.organizationId()));
        candidates.removeAll(seen);
        if (candidates.isEmpty()) {
            return 0;
        }
        // Records created while the rebuild was paging are active but were not seen; they must stay.
        reader.findActive(tenant.organizationId(), candidates).forEach(active -> candidates.remove(active.id()));
        return repository.markDeleted(table, tenant.organizationId(), candidates, SqlValues.now(clock));
    }

    private void forEach(TenantContext tenant, String module, Consumer<RecordView> action) {
        int page = 0;
        PageResponse<RecordView> result;
        do {
            result = records.list(tenant, module, Map.of(), ORDER, null, RecordScope.all(),
                    new PageQuery(page++, PAGE_SIZE));
            result.items().forEach(action);
        } while (page < result.totalPages());
    }
}
