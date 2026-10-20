package com.bm4a.platform.modules.metadata.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.authorization.RelationshipTuple;
import com.bm4a.platform.modules.authorization.RelationshipTuplesChanged;

/**
 * Upgrade aid for databases with modules created before module permissions existed (ADR-004, migration V8): writes
 * the derived module tuples of every tenant module through the regular outbox. Writes are idempotent, so running it
 * repeatedly is harmless. Enable once with {@code bm4a.metadata.sync-module-tuples-on-startup=true}.
 */
@Component
@ConditionalOnBooleanProperty("bm4a.metadata.sync-module-tuples-on-startup")
class ModuleTupleReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModuleTupleReconciler.class);
    private static final int PAGE = 200;

    private final MetadataRepository repository;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    ModuleTupleReconciler(MetadataRepository repository, ApplicationEventPublisher events,
            TransactionTemplate transactions) {
        this.repository = repository;
        this.events = events;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID after = null;
        int modules = 0;
        while (true) {
            UUID cursor = after;
            List<ModuleRow> page = transactions.execute(status -> {
                List<ModuleRow> rows = repository.listTenantModules(cursor, PAGE);
                List<RelationshipTuple> writes = new ArrayList<>();
                rows.forEach(row -> writes.addAll(MetadataService.moduleTuples(row)));
                if (!writes.isEmpty()) {
                    events.publishEvent(new RelationshipTuplesChanged(writes, List.of()));
                }
                return rows;
            });
            if (page == null || page.isEmpty()) {
                break;
            }
            modules += page.size();
            after = page.getLast().id();
        }
        log.info("Synchronized OpenFGA tuples of {} tenant modules", modules);
    }
}
