package com.bm4a.platform.modules.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.bm4a.platform.modules.shared.error.ApiException;

class ETagsTest {

    @Test
    void formatsAndParsesStrongAndWeakTags() {
        assertThat(ETags.of(7)).isEqualTo("\"7\"");
        assertThat(ETags.requireVersion("\"7\"")).isEqualTo(7);
        assertThat(ETags.requireVersion("W/\"12\"")).isEqualTo(12);
    }

    @Test
    void missingHeaderIsPreconditionRequired() {
        assertThatThrownBy(() -> ETags.requireVersion(null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
    }

    @Test
    void malformedHeaderIsBadRequest() {
        for (String value : new String[] { "7", "\"abc\"", "\"1\", \"2\"", "*" }) {
            assertThatThrownBy(() -> ETags.requireVersion(value))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.code()).isEqualTo("invalid_if_match"));
        }
    }
}
