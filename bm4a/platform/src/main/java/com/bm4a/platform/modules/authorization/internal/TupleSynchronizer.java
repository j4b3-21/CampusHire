package com.bm4a.platform.modules.authorization.internal;

import java.util.ArrayList;
import java.util.List;
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
    /** OpenFGA's default limit of tuples per write request ({@code OPENFGA_MAX_TUPLES_PER_WRITE}). */
    static final int MAX_TUPLES_PER_WRITE = 100;

    private final OpenFgaClient client;
    private final OpenFgaProperties properties;

    TupleSynchronizer(OpenFgaClient client, OpenFgaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(RelationshipTuplesChanged event) throws Exception {
        if (!event.hasChanges()) {
            return;
        }
        ClientWriteOptions options = new ClientWriteOptions()
                .onDuplicate(WriteRequestWrites.OnDuplicateEnum.IGNORE)
                .onMissing(WriteRequestDeletes.OnMissingEnum.IGNORE);
        try {
            if (event.writes().size() + event.deletes().size() <= MAX_TUPLES_PER_WRITE) {
                apply(new ClientWriteRequest()
                        .writes(event.writes().stream().map(TupleSynchronizer::write).toList())
                        .deletes(event.deletes().stream().map(TupleSynchronizer::delete).toList()), options);
                return;
            }
            // Larger changes are split; every chunk is idempotent, so a partially applied event is safely replayed.
            for (List<RelationshipTuple> chunk : chunks(event.deletes())) {
                apply(new ClientWriteRequest().deletes(chunk.stream().map(TupleSynchronizer::delete).toList()), options);
            }
            for (List<RelationshipTuple> chunk : chunks(event.writes())) {
                apply(new ClientWriteRequest().writes(chunk.stream().map(TupleSynchronizer::write).toList()), options);
            }
        } catch (Exception ex) {
            log.error("OpenFGA tuple synchronization failed; it will be retried: {}",
                    OpenFgaConfiguration.unwrap(ex).toString());
            throw ex;
        }
    }

    private void apply(ClientWriteRequest request, ClientWriteOptions options) throws Exception {
        client.write(request, options).get(properties.requestTimeout().toMillis() * 3, TimeUnit.MILLISECONDS);
    }

    private static List<List<RelationshipTuple>> chunks(List<RelationshipTuple> tuples) {
        List<List<RelationshipTuple>> chunks = new ArrayList<>();
        for (int i = 0; i < tuples.size(); i += MAX_TUPLES_PER_WRITE) {
            chunks.add(tuples.subList(i, Math.min(tuples.size(), i + MAX_TUPLES_PER_WRITE)));
        }
        return chunks;
    }

    private static ClientTupleKey write(RelationshipTuple tuple) {
        return new ClientTupleKey().user(tuple.user()).relation(tuple.relation())._object(tuple.object());
    }

    private static ClientTupleKeyWithoutCondition delete(RelationshipTuple tuple) {
        return new ClientTupleKeyWithoutCondition().user(tuple.user()).relation(tuple.relation())
                ._object(tuple.object());
    }
}
