package io.github.timganev.switchboard.gateway.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Issues, lists and revokes keys for an admin; a key is shown only in the answer that issues it.
 */
@RestController
@RequestMapping("/admin/keys")
@ConditionalOnProperty(name = "switchboard.auth.type", havingValue = "api-key")
class ApiKeyController {

    private final ApiKeys apiKeys;
    private final byte[] adminToken;

    ApiKeyController(ApiKeys apiKeys, AuthProperties properties) {
        this.apiKeys = apiKeys;
        this.adminToken = properties.adminToken().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    ResponseEntity<ApiKeys.IssuedKey> issue(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                            @RequestBody IssueRequest request) {
        checkAdmin(authorization);
        return ResponseEntity.status(HttpStatus.CREATED).body(apiKeys.issue(request.team()));
    }

    @GetMapping
    List<ApiKeys.KeyInfo> list(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                               @RequestParam String team) {
        checkAdmin(authorization);
        return apiKeys.list(team);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> revoke(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                @PathVariable String id) {
        checkAdmin(authorization);
        return apiKeys.revoke(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // Compared in constant time, so the answer time does not tell how much of a guess was right.
    private void checkAdmin(String authorization) {
        String token = authorization == null || !authorization.startsWith("Bearer ") ? "" : authorization.substring("Bearer ".length());
        if (!MessageDigest.isEqual(adminToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("Admin token required");
        }
    }

    record IssueRequest(String team) {
    }
}
