package com.bm4a.platform.modules.authorization.internal;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.bm4a.platform.modules.authorization.RelationshipTuple;
import com.bm4a.platform.modules.authorization.RelationshipTuplesChanged;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.configuration.ClientWriteOptions;
import dev.openfga.sdk.api.model.WriteRequestDeletes;
import dev.openfga.sdk.api.model.WriteRequestWrites;

/**
 * Applies tuple changes to OpenFGA after the business transaction committed, in the calling thread, so the caller's
 * next request already observes them. The Spring Modulith publication registry records the event before commit and
 * marks it completed only when this listener returns normally; failures stay in {@code event_publication} and are
 * retried by {@link TupleResubmissionJob}. Writes and deletes are idempotent ({@code on_duplicate}/{@code on_missing}
 * = ignore), so replays are safe.
 */
@Component
class TupleSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(TupleSynchronizer.class);

    private final OpenFgaClient client;
    private final OpenFgaProperties properties;

    TupleSynchronizer(OpenFgaClient client, OpenFgaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(RelationshipTuplesChanged event) throws Exception {
        ClientWriteRequest request = new ClientWriteRequest()
                .writes(event.writes().stream().map(TupleSynchronizer::write).toList())
                .deletes(event.deletes().stream().map(TupleSynchronizer::delete).toList());
        ClientWriteOptions options = new ClientWriteOptions()
                .onDuplicate(WriteRequestWrites.OnDuplicateEnum.IGNORE)
                .onMissing(WriteRequestDeletes.OnMissingEnum.IGNORE);
        try {
            client.write(request, options).get(properties.requestTimeout().toMillis() * 3, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            log.error("OpenFGA tuple synchronization failed; it will be retried: {}",
                    OpenFgaConfiguration.unwrap(ex).toString());
            throw ex;
        }
    }

    private static ClientTupleKey write(RelationshipTuple tuple) {
        return new ClientTupleKey().user(tuple.user()).relation(tuple.relation())._object(tuple.object());
    }

    private static ClientTupleKeyWithoutCondition delete(RelationshipTuple tuple) {
        return new ClientTupleKeyWithoutCondition().user(tuple.user()).relation(tuple.relation())
                ._object(tuple.object());
    }
}
