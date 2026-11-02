package io.github.timganev.switchboard.guardian.decision;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.timganev.switchboard.commons.event.DecisionModelRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks a local model for personal data with the words that prove it; a claim whose words are not in the text is dropped.
 */
public class LocalDecisionModel implements DecisionModel {

    private static final Logger log = LoggerFactory.getLogger(LocalDecisionModel.class);
    // Changes whenever the prompt or the categories change, so traces show which question was asked.
    static final String VERSION = "1";
    static final Set<String> CATEGORIES = Set.of("NAME", "HEALTH");
    private static final Pattern CLAIM = Pattern.compile("(?m)^\\W*(NAME|HEALTH)\\W*:\\s*(.+?)\\s*$");
    private static final Pattern CAPITALISED = Pattern.compile("\\p{Lu}\\p{L}*(?:[ -]\\p{Lu}\\p{L}*)*");
    // Reasoning models spend tokens thinking before they answer.
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
                    .body(new Request(model, List.of(new Message("user", prompt(text))), 0.0, MAX_TOKENS, reasoningEffort))
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

    // A reply with neither NONE nor a claim is not an answer; a name must be capitalised words, not a role like "the customer".
    static Classification categories(String reply, String text) {
        Set<String> found = new HashSet<>();
        boolean claimed = false;
        Matcher matcher = CLAIM.matcher(reply);
        while (matcher.find()) {
            claimed = true;
            String category = matcher.group(1);
            String words = matcher.group(2).replaceAll("^[\"'“”]+|[\"'“”.]+$", "");
            boolean proven = !words.isBlank() && text.toLowerCase().contains(words.toLowerCase())
                    && (!category.equals("NAME") || CAPITALISED.matcher(words).matches());
            if (proven) {
                found.add(category);
            }
        }
        if (!claimed && !reply.toUpperCase().contains("NONE")) {
            return Classification.FAILED;
        }
        return new Classification(found, false);
    }

    static String prompt(String text) {
        return """
                You check a message for personal data that pattern matching cannot find. Report a category only if the \
                message really contains it:
                NAME - a person's first name or surname, written in the message. Roles such as "the customer", jobs and \
                towns are not names.
                HEALTH - a health condition, a diagnosis or the medical care of a person. Payments, loans and jobs are not \
                health data.

                Message:
                \"\"\"
                %s
                \"\"\"

                For each category found, reply with one line: the category, a colon, and the exact words from the message, \
                for example "NAME: Anna Georgieva". Reply NONE if no category applies.""".formatted(text);
    }

    // Left out when null, since not every OpenAI-compatible server knows it.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Request(String model, List<Message> messages, double temperature, @JsonProperty("max_tokens") int maxTokens,
                   @JsonProperty("reasoning_effort") String reasoningEffort) {
    }

    record Message(String role, String content) {
    }
}
