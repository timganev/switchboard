package io.github.timganev.switchboard.leaklab.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

/**
 * One OpenAI-compatible chat model at temperature 0; it reports tokens and time, so a strategy's cost and latency can be measured.
 */
public class ModelClient {

    private final RestClient restClient;
    private final String model;
    private final String reasoningEffort;

    public ModelClient(RestClient restClient, String model, String reasoningEffort) {
        this.restClient = restClient;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
    }

    public Completion complete(String prompt, int maxTokens) {
        Request request = new Request(model, List.of(new Message("user", prompt)), 0.0, maxTokens, reasoningEffort);
        long started = System.nanoTime();
        JsonNode answer = restClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        if (answer == null) {
            throw new IllegalStateException("Empty answer from " + model);
        }
        return new Completion(answer.path("choices").path(0).path("message").path("content").asString(),
                answer.path("usage").path("prompt_tokens").asInt(), answer.path("usage").path("completion_tokens").asInt(), took);
    }

    // Left out when null, since not every OpenAI-compatible server knows it.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Request(String model, List<Message> messages, double temperature, @JsonProperty("max_tokens") int maxTokens,
                   @JsonProperty("reasoning_effort") String reasoningEffort) {
    }

    record Message(String role, String content) {
    }
}
