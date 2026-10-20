package com.bm4a.platform.modules.shared.error;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a problem response outside Spring MVC (for example from security filters), using the same flat JSON
 * shape that MVC produces.
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static void write(HttpServletResponse response, ProblemDetail problem, JsonMapper jsonMapper)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType() == null ? "about:blank" : problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        if (problem.getDetail() != null) {
            body.put("detail", problem.getDetail());
        }
        if (problem.getInstance() != null) {
            body.put("instance", problem.getInstance().toString());
        }
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
