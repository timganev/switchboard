package io.github.timganev.switchboard.trace.api;

import io.github.timganev.switchboard.trace.TraceStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;

@RestController
@RequestMapping("/v1/calls")
class CallController {

    private static final int MAX_LIMIT = 500;

    private final TraceStore store;

    CallController(TraceStore store) {
        this.store = store;
    }

    @GetMapping
    List<JsonNode> recent(@RequestParam(required = false) String team, @RequestParam(defaultValue = "50") int limit) {
        return store.recent(team, Math.clamp(limit, 1, MAX_LIMIT));
    }

    @GetMapping("/{callId}")
    ResponseEntity<JsonNode> call(@PathVariable String callId) {
        return ResponseEntity.of(store.find(callId));
    }
}
