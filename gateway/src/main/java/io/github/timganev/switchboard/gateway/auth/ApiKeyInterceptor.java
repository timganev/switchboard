package io.github.timganev.switchboard.gateway.auth;

import io.github.timganev.switchboard.gateway.events.CallEvents;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Lets a request through only with a valid key, and takes the team from the key instead of a header.
 */
class ApiKeyInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";
    // Anthropic clients, Claude Code among them, send the key in this header.
    private static final String ANTHROPIC_KEY = "x-api-key";

    private final ApiKeys apiKeys;

    ApiKeyInterceptor(ApiKeys apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String key = key(request);
        if (key == null) {
            throw new UnauthorizedException("Missing API key: send it as Authorization: Bearer <key> or x-api-key");
        }
        String team = apiKeys.team(key).orElseThrow(() -> new UnauthorizedException("Invalid or revoked API key"));
        request.setAttribute(CallEvents.TEAM_ATTRIBUTE, team);
        return true;
    }

    static String key(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return blankToNull(authorization.substring(BEARER.length()));
        }
        return blankToNull(request.getHeader(ANTHROPIC_KEY));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
