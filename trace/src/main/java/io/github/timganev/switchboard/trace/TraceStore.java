package io.github.timganev.switchboard.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One OpenSearch document per call: the gateway's facts and the guardian's verdict are merged into it as they arrive.
 */
@Component
public class TraceStore {

    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);

    static final String INDEX = "switchboard-calls";
    // Both topics may update the same document at once.
    private static final int RETRY_ON_CONFLICT = 3;

    private final RestClient openSearch;
    private final JsonMapper jsonMapper;
    private volatile boolean indexReady;

    public TraceStore(RestClient openSearchRestClient, JsonMapper jsonMapper) {
        this.openSearch = openSearchRestClient;
        this.jsonMapper = jsonMapper;
    }

    public void merge(String callId, Object fields) {
        ensureIndex();
        ObjectNode body = jsonMapper.createObjectNode().put("doc_as_upsert", true);
        body.set("doc", jsonMapper.valueToTree(fields));
        openSearch.post()
                .uri("/{index}/_update/{id}?retry_on_conflict={retries}", INDEX, callId, RETRY_ON_CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    public Optional<JsonNode> find(String callId) {
        try {
            JsonNode document = openSearch.get().uri("/{index}/_doc/{id}", INDEX, callId).retrieve().body(JsonNode.class);
            return Optional.ofNullable(document).map(json -> json.get("_source"));
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        }
    }

    public List<JsonNode> recent(String team, int limit) {
        ObjectNode search = jsonMapper.createObjectNode().put("size", limit);
        search.putArray("sort").addObject().putObject("timestamp").put("order", "desc").put("missing", "_last");
        if (team == null) {
            search.putObject("query").putObject("match_all");
        } else {
            search.putObject("query").putObject("term").put("team", team);
        }
        JsonNode result;
        try {
            result = openSearch.post()
                    .uri("/{index}/_search", INDEX)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(search)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound exception) {
            return List.of();
        }
        List<JsonNode> calls = new ArrayList<>();
        result.path("hits").path("hits").forEach(hit -> calls.add(hit.get("_source")));
        return calls;
    }

    // Created before the first read where possible; while OpenSearch is down, the first write creates it.
    @EventListener(ApplicationReadyEvent.class)
    void createIndex() {
        try {
            ensureIndex();
        } catch (RuntimeException exception) {
            log.warn("Could not create {} yet, the first write will: {}", INDEX, exception.toString());
        }
    }

    // Created with an explicit mapping before the first write, or OpenSearch would guess the field types.
    private void ensureIndex() {
        if (indexReady) {
            return;
        }
        try {
            openSearch.put().uri("/{index}", INDEX).contentType(MediaType.APPLICATION_JSON).body(mapping()).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() != HttpStatus.BAD_REQUEST
                    || !exception.getResponseBodyAsString().contains("resource_already_exists_exception")) {
                throw exception;
            }
        }
        // A new index answers 503 until its shard has started.
        openSearch.get().uri("/_cluster/health/{index}?wait_for_status=yellow&timeout=30s", INDEX).retrieve().toBodilessEntity();
        indexReady = true;
    }

    private byte[] mapping() {
        try (InputStream in = new ClassPathResource("opensearch/calls-index.json").getInputStream()) {
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("The index mapping is missing", exception);
        }
    }
}
