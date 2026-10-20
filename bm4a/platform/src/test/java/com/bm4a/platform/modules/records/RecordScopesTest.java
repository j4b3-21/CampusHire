package com.bm4a.platform.modules.records;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bm4a.platform.modules.access.RecordScope;
import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldConfig.SelectOption;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.ModuleAccessMode;
import com.bm4a.platform.modules.metadata.ModuleSchema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class RecordScopesTest {

    static final UUID DEPT = UUID.fromString("6f1c2a52-6a7e-4c55-9d36-0d8f8b1f7e10");
    static final ModuleSchema MODULE = new ModuleSchema(UUID.randomUUID(), UUID.randomUUID(), "student", "Student",
            "Students", true, 1, ModuleAccessMode.ROLES, List.of(
                    field("department", FieldType.LOOKUP, FieldConfig.empty()),
                    field("status", FieldType.SINGLE_SELECT, new FieldConfig(null, null, null, null, null,
                            List.of(new SelectOption("draft", "Draft", null), new SelectOption("live", "Live", null)),
                            null, null, null, null, null)),
                    field("tags", FieldType.MULTI_SELECT, new FieldConfig(null, null, null, null, null,
                            List.of(new SelectOption("x", "X", null), new SelectOption("y", "Y", null)), null, null,
                            null, null, null))));

    @Test
    void predicatesBindEveryValueAndFailClosed() {
        assertThat(RecordScopes.predicate(RecordScope.all(), MODULE, "r", "s").sql()).isEqualTo("TRUE");
        assertThat(RecordScopes.predicate(RecordScope.none(), MODULE, "r", "s").sql()).isEqualTo("(FALSE)");

        UUID user = UUID.randomUUID();
        RecordScopes.Predicate own = RecordScopes.predicate(RecordScope.own(user), MODULE, "r", "s");
        assertThat(own.sql()).isEqualTo("(r.owner_user_id = :s0)");
        assertThat(own.params()).containsEntry("s0", user);

        RecordScopes.Predicate attribute = RecordScopes.predicate(
                RecordScope.attribute("department", List.of(DEPT.toString().toUpperCase(), "' OR 1=1 --")), MODULE,
                "r", "s");
        assertThat(attribute.sql()).isEqualTo("((r.data @> CAST(:s0 AS jsonb)))");
        assertThat(attribute.params()).containsEntry("s0", "{\"department\":\"" + DEPT + "\"}");

        assertThat(RecordScopes.predicate(RecordScope.attribute("department", List.of()), MODULE, "r", "s").sql())
                .isEqualTo("(FALSE)");
        assertThat(RecordScopes.predicate(RecordScope.attribute("unknown", List.of("x")), MODULE, "r", "s").sql())
                .isEqualTo("(FALSE)");

        RecordScopes.Predicate ids = RecordScopes.predicate(
                RecordScope.attribute(RecordScope.ID_FIELD, List.of(DEPT.toString(), "nope")), MODULE, "x", "p");
        assertThat(ids.sql()).isEqualTo("(x.id IN (:p0))");
        assertThat(ids.params()).containsEntry("p0", List.of(DEPT));

        RecordScopes.Predicate where = RecordScopes.predicate(RecordScope.all().withWhere(
                Map.of("status", List.of(text("draft"), text("live")))), MODULE, "r", "s");
        assertThat(where.sql()).isEqualTo("((r.data @> CAST(:s0 AS jsonb) OR r.data @> CAST(:s1 AS jsonb)))");
    }

    @Test
    void inMemoryMatchingUsesTheSameContainmentSemantics() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ObjectNode data = (ObjectNode) JsonMapper.shared().readTree(
                "{\"department\":\"" + DEPT + "\",\"status\":\"live\",\"tags\":[\"x\",\"y\"]}");

        assertThat(RecordScopes.matches(RecordScope.all(), MODULE, id, owner, data)).isTrue();
        assertThat(RecordScopes.matches(RecordScope.none(), MODULE, id, owner, data)).isFalse();
        assertThat(RecordScopes.matches(RecordScope.own(owner), MODULE, id, owner, data)).isTrue();
        assertThat(RecordScopes.matches(RecordScope.own(UUID.randomUUID()), MODULE, id, owner, data)).isFalse();
        assertThat(RecordScopes.matches(RecordScope.attribute("department", List.of("a", DEPT.toString())), MODULE,
                id, owner, data)).isTrue();
        assertThat(RecordScopes.matches(RecordScope.attribute("department", List.of(UUID.randomUUID().toString())),
                MODULE, id, owner, data)).isFalse();
        assertThat(RecordScopes.matches(RecordScope.attribute("tags", List.of("y")), MODULE, id, owner, data)).isTrue();
        assertThat(RecordScopes.matches(RecordScope.attribute(RecordScope.ID_FIELD, List.of(id.toString())), MODULE,
                id, owner, data)).isTrue();
        assertThat(RecordScopes.matches(RecordScope.all().withWhere(Map.of("status", List.of(text("draft")))), MODULE,
                id, owner, data)).isFalse();
        assertThat(RecordScopes.matches(RecordScope.own(owner).withWhere(Map.of("tags",
                List.of(JsonNodeFactory.instance.arrayNode().add("x")))), MODULE, id, owner, data)).isTrue();
    }

    private static JsonNode text(String value) {
        return JsonNodeFactory.instance.stringNode(value);
    }

    private static FieldSchema field(String key, FieldType type, FieldConfig config) {
        return new FieldSchema(UUID.randomUUID(), key, key, type, FieldStatus.ACTIVE, false, false, false, false, false,
                true, false, null, config, 0);
    }
}
