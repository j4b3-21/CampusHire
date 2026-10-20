package com.bm4a.platform.modules.shared.web;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.shared.error.Problems;

import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Translates every failure into an RFC 9457 problem response. Internal details (stack traces, SQL, class names)
 * are logged with the correlation ID but never returned to the client.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        if (ex.status().is5xxServerError()) {
            log.warn("Request failed: {} {}", ex.code(), ex.getMessage());
        }
        return ResponseEntity.status(ex.status())
                .body(Problems.of(ex.status(), ex.code(), ex.getMessage(), ex.violations()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Problems.of(HttpStatus.FORBIDDEN, "access_denied", "Access denied"));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Problems.of(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> violations.add(
                new FieldViolation(error.getField(), codeOf(error.getCode()), error.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors().forEach(error -> violations.add(
                new FieldViolation(error.getObjectName(), codeOf(error.getCode()), error.getDefaultMessage())));
        return validationResponse(violations);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> violations.add(
                    new FieldViolation(name, codeOf(error.getCodes() == null ? null : error.getCodes()[0]),
                            error.getDefaultMessage())));
        }
        return validationResponse(violations);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Throwable cause = ex.getCause();
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return validationResponse(List.of(new FieldViolation(unknown.getPropertyName(), "unknown_property",
                    "Property is not recognized or not writable")));
        }
        return ResponseEntity.badRequest().body(
                Problems.of(HttpStatus.BAD_REQUEST, "malformed_request", "Request body is missing or malformed"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && (problem.getProperties() == null
                || !problem.getProperties().containsKey("code"))) {
            ProblemDetail enriched = Problems.of(statusCode, defaultCode(statusCode), problem.getDetail());
            return super.handleExceptionInternal(ex, enriched, headers, statusCode, request);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationResponse(List<FieldViolation> violations) {
        return ResponseEntity.badRequest().body(
                Problems.of(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed", violations));
    }

    private static String codeOf(@Nullable String constraint) {
        if (constraint == null) {
            return "invalid";
        }
        int dot = constraint.lastIndexOf('.');
        String simple = dot >= 0 ? constraint.substring(dot + 1) : constraint;
        return simple.isEmpty() ? "invalid" : Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    private static String defaultCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "bad_request";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 406 -> "not_acceptable";
            case 413 -> "file_too_large";
            case 415 -> "unsupported_media_type";
            default -> status.is5xxServerError() ? "internal_error" : "request_failed";
        };
    }
}
