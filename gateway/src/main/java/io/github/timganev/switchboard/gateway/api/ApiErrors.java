package io.github.timganev.switchboard.gateway.api;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.gateway.backend.NoBackendAvailableException;
import io.github.timganev.switchboard.gateway.budget.BudgetExceededException;
import io.github.timganev.switchboard.gateway.events.CallEvents;
import io.github.timganev.switchboard.gateway.guardian.GuardianUnavailableException;
import io.github.timganev.switchboard.gateway.guardian.RequestBlockedException;
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
    private final CallEvents callEvents;

    ApiErrors(JsonMapper jsonMapper, CallEvents callEvents) {
        this.jsonMapper = jsonMapper;
        this.callEvents = callEvents;
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

    @ExceptionHandler(RequestBlockedException.class)
    ResponseEntity<ObjectNode> blocked(RequestBlockedException exception, HttpServletRequest request) {
        callEvents.failed(request, HttpStatus.FORBIDDEN.value());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(SwitchboardHeaders.ROUTE, SwitchboardHeaders.route(Route.BLOCK, exception.reasons()))
                .body(body(request, "permission_error",
                        "The request was blocked because it contains " + SwitchboardHeaders.types(exception.reasons())));
    }

    @ExceptionHandler(GuardianUnavailableException.class)
    ResponseEntity<ObjectNode> guardianUnavailable(GuardianUnavailableException exception, HttpServletRequest request) {
        log.warn("Guardian call failed: {}", exception.getCause().toString());
        return error(request, HttpStatus.SERVICE_UNAVAILABLE, "api_error",
                "The privacy guardian is unavailable, so the request was not sent");
    }

    @ExceptionHandler(BudgetExceededException.class)
    ResponseEntity<ObjectNode> budgetExceeded(BudgetExceededException exception, HttpServletRequest request) {
        return error(request, HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error", exception.getMessage());
    }

    @ExceptionHandler(NoBackendAvailableException.class)
    ResponseEntity<ObjectNode> noBackend(NoBackendAvailableException exception, HttpServletRequest request) {
        return error(request, HttpStatus.SERVICE_UNAVAILABLE, "api_error", exception.getMessage());
    }

    private ResponseEntity<ObjectNode> error(HttpServletRequest request, HttpStatus status, String type, String message) {
        callEvents.failed(request, status.value());
        return ResponseEntity.status(status).body(body(request, type, message));
    }

    private ObjectNode body(HttpServletRequest request, String type, String message) {
        ObjectNode body = jsonMapper.createObjectNode();
        if (request.getRequestURI().endsWith("/messages")) {
            body.put("type", "error").putObject("error").put("type", type).put("message", message);
        } else {
            body.putObject("error").put("type", type).put("message", message);
        }
        return body;
    }
}
