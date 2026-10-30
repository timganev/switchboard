package io.github.timganev.switchboard.gateway.api;

import io.github.timganev.switchboard.gateway.backend.NoBackendAvailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Answers errors in the shape the caller's API expects, so OpenAI and Anthropic clients can parse them.
 */
@RestControllerAdvice
class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    private final JsonMapper jsonMapper;

    ApiErrors(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ObjectNode> invalidRequest(IllegalArgumentException exception, HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST, "invalid_request_error", exception.getMessage());
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<ObjectNode> backendFailure(RestClientException exception, HttpServletRequest request) {
        Throwable cause = exception.getMostSpecificCause();
        log.warn("Backend call failed: {}: {}", cause.getClass().getSimpleName(), cause.getMessage());
        return error(request, HttpStatus.BAD_GATEWAY, "api_error", "The model backend failed to answer");
    }

    @ExceptionHandler(NoBackendAvailableException.class)
    ResponseEntity<ObjectNode> noBackend(NoBackendAvailableException exception, HttpServletRequest request) {
        return error(request, HttpStatus.SERVICE_UNAVAILABLE, "api_error", exception.getMessage());
    }

    private ResponseEntity<ObjectNode> error(HttpServletRequest request, HttpStatus status, String type, String message) {
        ObjectNode body = jsonMapper.createObjectNode();
        if (request.getRequestURI().endsWith("/messages")) {
            body.put("type", "error").putObject("error").put("type", type).put("message", message);
        } else {
            body.putObject("error").put("type", type).put("message", message);
        }
        return ResponseEntity.status(status).body(body);
    }
}
