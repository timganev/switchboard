package io.github.timganev.switchboard.console;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shows one prompt's way through Switchboard: what the guardian lets out, and what the caller gets back from the gateway.
 */
public class Playground {

    private static final String TEAM_HEADER = "X-Switchboard-Team";
    private static final String ROUTE_HEADER = "X-Switchboard-Route";
    private static final String FINDINGS_HEADER = "X-Switchboard-Response-Findings";

    private final RestClient gateway;
    private final RestClient guardian;
    private final ConsoleProperties properties;
    private final JsonMapper jsonMapper;
    private volatile String apiKey;

    public Playground(ConsoleProperties properties, JsonMapper jsonMapper) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(properties.timeout());
        this.gateway = RestClient.builder().baseUrl(properties.gatewayUrl().toString()).requestFactory(requestFactory).build();
        this.guardian = RestClient.builder().baseUrl(properties.guardianUrl().toString()).requestFactory(requestFactory).build();
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.apiKey = properties.apiKey();
    }

    public Result run(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Write a prompt first");
        }
        // The gateway asks the guardian the same question; asking here too shows what left, which the gateway never returns.
        JsonNode inspection = guardian.post().uri("/v1/inspect").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", properties.model(), "messages",
                        List.of(Map.of("role", "USER", "parts", List.of(Map.of("type", "text", "text", prompt))))))
                .retrieve()
                .body(JsonNode.class);
        List<Reason> reasons = new ArrayList<>();
        inspection.path("reasons").forEach(reason -> reasons.add(new Reason(reason.path("type").asString(), reason.path("count").asInt())));
        List<Swap> swaps = new ArrayList<>();
        inspection.path("substitutions").forEach(swap -> swaps.add(new Swap(swap.path("type").asString(), swap.path("value").asString(),
                swap.path("surrogate").asString())));
        JsonNode sentText = inspection.path("request").path("messages").path(0).path("parts").path(0).path("text");
        String sent = sentText.isMissingNode() || sentText.isNull() ? null : sentText.asString();

        long started = System.nanoTime();
        Answer answer = ask(prompt);
        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new Result(inspection.path("route").asString(), reasons, sent, swaps, answer.status(), answer.route(), answer.findings(),
                answer.text(), answer.error(), latencyMs);
    }

    private Answer ask(String prompt) {
        Map<String, Object> body = Map.of("model", properties.model(), "messages", List.of(Map.of("role", "user", "content", prompt)));
        return gateway.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    headers.set(TEAM_HEADER, properties.team());
                    String key = key();
                    if (key != null) {
                        headers.setBearerAuth(key);
                    }
                })
                .body(body)
                .exchange((request, response) -> {
                    JsonNode json = readJson(response.getBody().readAllBytes());
                    HttpHeaders headers = response.getHeaders();
                    boolean ok = response.getStatusCode().is2xxSuccessful();
                    return new Answer(response.getStatusCode().value(), headers.getFirst(ROUTE_HEADER), headers.getFirst(FINDINGS_HEADER),
                            ok ? json.path("choices").path(0).path("message").path("content").asString() : null,
                            ok ? null : json.path("error").path("message").asString("The gateway answered " + response.getStatusCode().value()));
                });
    }

    // Issued once for the playground's team when only the admin token is configured; the browser never sees it.
    private String key() {
        if (apiKey == null && properties.adminToken() != null) {
            synchronized (this) {
                if (apiKey == null) {
                    JsonNode issued = gateway.post().uri("/admin/keys")
                            .headers(headers -> headers.setBearerAuth(properties.adminToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("team", properties.team()))
                            .retrieve()
                            .body(JsonNode.class);
                    apiKey = issued.path("key").asString();
                }
            }
        }
        return apiKey;
    }

    private JsonNode readJson(byte[] bytes) {
        try {
            return jsonMapper.readTree(bytes);
        } catch (RuntimeException exception) {
            return jsonMapper.createObjectNode();
        }
    }

    public record Result(String route, List<Reason> reasons, String sent, List<Swap> swaps, int status, String gatewayRoute,
                         String responseFindings, String answer, String error, long latencyMs) {
    }

    public record Reason(String type, int count) {
    }

    public record Swap(String type, String value, String surrogate) {
    }

    private record Answer(int status, String route, String findings, String text, String error) {
    }
}
