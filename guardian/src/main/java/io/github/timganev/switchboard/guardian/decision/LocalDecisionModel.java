package io.github.timganev.switchboard.guardian.decision;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.timganev.switchboard.commons.event.DecisionModelRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Asks a local model for personal data with the words that prove it; a claim whose words are not in the text is dropped.
 */
public class LocalDecisionModel implements DecisionModel {

    private static final Logger log = LoggerFactory.getLogger(LocalDecisionModel.class);
    // Changes whenever the prompt or the categories change, so traces show which question was asked.
    static final String VERSION = "2";
    private static final Pattern CAPITALISED = Pattern.compile("\\p{Lu}\\p{L}*(?:[ -]\\p{Lu}\\p{L}*)*");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // The server may only produce JSON of this shape, so the model cannot reason in prose before it answers.
    private static final ResponseFormat FORMAT = new ResponseFormat("json_schema", new JsonSchema("decision", Map.of(
            "type", "object",
            "properties", Map.of(
                    "names", Map.of("type", "array", "items", Map.of("type", "string")),
                    "health", Map.of("type", "array", "items", Map.of("type", "string"))),
            "required", List.of("names", "health"),
            "additionalProperties", false), true));
    // Room for a reasoning model that thinks even when asked not to.
    private static final int MAX_TOKENS = 4000;

    private final RestClient restClient;
    private final String model;
    private final String reasoningEffort;

    public LocalDecisionModel(RestClient restClient, String model, String reasoningEffort) {
        this.restClient = restClient;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
    }

    @Override
    public Classification classify(String text) {
        try {
            JsonNode answer = restClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(model, List.of(new Message("user", prompt(text))), 0.0, MAX_TOKENS, reasoningEffort, FORMAT))
                    .retrieve()
                    .body(JsonNode.class);
            String reply = answer == null ? "" : answer.path("choices").path(0).path("message").path("content").asString();
            return categories(reply, text);
        } catch (RestClientException exception) {
            log.warn("The decision model failed, so the request is treated as sensitive: {}", exception.getMessage());
            return Classification.FAILED;
        }
    }

    @Override
    public DecisionModelRef ref() {
        return new DecisionModelRef(model, VERSION);
    }

    // A reply that is not the expected JSON is not an answer; a name must be capitalised words, not a role like "the customer".
    static Classification categories(String reply, String text) {
        JsonNode node;
        try {
            node = JSON.readTree(reply);
        } catch (JacksonException exception) {
            return Classification.FAILED;
        }
        if (node == null || !node.path("names").isArray() || !node.path("health").isArray()) {
            return Classification.FAILED;
        }
        Set<String> found = new HashSet<>();
        List<String> names = node.path("names").valueStream().map(JsonNode::asString).map(String::strip)
                .filter(words -> inText(words, text) && CAPITALISED.matcher(words).matches())
                .distinct()
                .toList();
        if (!names.isEmpty()) {
            found.add("NAME");
        }
        if (node.path("health").valueStream().map(JsonNode::asString).anyMatch(words -> inText(words, text))) {
            found.add("HEALTH");
        }
        return new Classification(found, names, false);
    }

    private static boolean inText(String words, String text) {
        return !words.isBlank() && text.toLowerCase().contains(words.strip().toLowerCase());
    }

    static String prompt(String text) {
        return """
                You check a message for personal data that pattern matching cannot find.
                names: first names or surnames of real people written in the message. Roles such as "the customer", jobs \
                and towns are not names.
                health: words about a health condition, a diagnosis or the medical care of a person. Payments, loans and \
                jobs are not health data.
                Copy the exact words from the message. Use empty lists when nothing applies.

                Message:
                \"\"\"
                %s
                \"\"\"""".formatted(text);
    }

    // Left out when null, since not every OpenAI-compatible server knows it.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Request(String model, List<Message> messages, double temperature, @JsonProperty("max_tokens") int maxTokens,
                   @JsonProperty("reasoning_effort") String reasoningEffort, @JsonProperty("response_format") ResponseFormat responseFormat) {
    }

    record Message(String role, String content) {
    }

    record ResponseFormat(String type, @JsonProperty("json_schema") JsonSchema jsonSchema) {
    }

    record JsonSchema(String name, Map<String, Object> schema, boolean strict) {
    }
}
