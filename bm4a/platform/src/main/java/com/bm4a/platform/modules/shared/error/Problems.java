package com.bm4a.platform.modules.shared.error;

import java.util.List;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import com.bm4a.platform.modules.shared.web.CorrelationId;

/**
 * Builds RFC 9457 problem details with the BM4A extension members {@code code}, {@code errors} and
 * {@code correlationId}.
 */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        return of(status, code, detail, List.of());
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail, List<FieldViolation> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        String correlationId = CorrelationId.current();
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}
