package io.github.timganev.switchboard.trace;

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
