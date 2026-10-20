package com.bm4a.platform.modules.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RedactionTest {

    @Test
    void redactsCredentialLikeKeysRecursively() {
        Map<String, Object> redacted = Redaction.redact(Map.of(
                "name", "visible",
                "password", "hunter2",
                "nested", Map.of("accessToken", "abc", "Client-Secret", "s3cr3t", "keep", 1),
                "list", List.of(Map.of("apiKey", "k", "id", 7))));

        assertThat(redacted).containsEntry("name", "visible").containsEntry("password", AuditEntry.REDACTED);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) redacted.get("nested");
        assertThat(nested)
                .containsEntry("accessToken", AuditEntry.REDACTED)
                .containsEntry("Client-Secret", AuditEntry.REDACTED)
                .containsEntry("keep", 1);
        assertThat(((List<?>) redacted.get("list")).get(0))
                .isEqualTo(Map.of("apiKey", AuditEntry.REDACTED, "id", 7));
    }
}
