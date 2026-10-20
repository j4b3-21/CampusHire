package com.bm4a.platform.modules.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bm4a.platform.modules.shared.error.ApiException;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MembershipAttributesTest {

    @Test
    void canonicalizesStringsAndArrays() {
        ObjectNode canonical = MembershipAttributes.canonical(JsonMapper.shared().readTree(
                "{\"department\":\" cse \",\"company\":[\"a\",\"b\",\"a\"],\"empty\":[],\"gone\":null}"), "attributes");
        assertThat(canonical.toString()).isEqualTo("{\"department\":\"cse\",\"company\":[\"a\",\"b\"]}");
        assertThat(MembershipAttributes.asMap(canonical))
                .isEqualTo(Map.of("department", "cse", "company", List.of("a", "b")));
        assertThat(MembershipAttributes.canonical(null, "attributes").isEmpty()).isTrue();
    }

    @Test
    void rejectsInvalidNamesAndValues() {
        for (String invalid : new String[] { "[1]", "{\"Bad\":\"x\"}", "{\"n\":1}", "{\"n\":[1]}", "{\"n\":\"\"}",
                "{\"n\":{\"x\":1}}", "{\"n\":\"" + "x".repeat(201) + "\"}" }) {
            assertThatThrownBy(() -> MembershipAttributes.canonical(JsonMapper.shared().readTree(invalid), "attributes"))
                    .as(invalid).isInstanceOf(ApiException.class);
        }
    }
}
