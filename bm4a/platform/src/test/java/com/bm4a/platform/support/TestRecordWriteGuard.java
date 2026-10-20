package com.bm4a.platform.support;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.bm4a.platform.modules.records.RecordWrite;
import com.bm4a.platform.modules.records.RecordWriteGuard;
import com.bm4a.platform.modules.shared.error.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * Write guard used by the shared test context. It only observes modules whose API key starts with {@code guarded},
 * records what it saw, and vetoes payloads whose {@code title} is {@value #VETO}.
 */
public class TestRecordWriteGuard implements RecordWriteGuard {

    public static final String VETO = "forbidden";

    private final List<RecordWrite> writes = new CopyOnWriteArrayList<>();

    @Override
    public void beforeWrite(RecordWrite write) {
        if (!write.module().apiKey().startsWith("guarded")) {
            return;
        }
        writes.add(write);
        JsonNode title = write.after().get("title");
        if (title != null && VETO.equals(title.asString())) {
            throw ApiException.conflict("vetoed", "Vetoed by the test guard");
        }
    }

    public List<RecordWrite> writes(UUID organizationId) {
        return writes.stream().filter(write -> write.tenant().organizationId().equals(organizationId)).toList();
    }
}
