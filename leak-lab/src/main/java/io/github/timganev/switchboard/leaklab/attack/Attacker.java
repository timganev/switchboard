package io.github.timganev.switchboard.leaklab.attack;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.timganev.switchboard.leaklab.LeakLabProperties;
import io.github.timganev.switchboard.leaklab.dataset.Person;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A second model that gets only what left the boundary and a list of people, and names the one the message is about.
 */
@Component
public class Attacker {

    private static final Pattern CANDIDATE = Pattern.compile("\\bP\\d{2,}\\b");
    // Reasoning models spend tokens thinking before they answer.
    private static final int MAX_TOKENS = 4000;

    private final RestClient attackerRestClient;
    private final String model;

    public Attacker(RestClient attackerRestClient, LeakLabProperties properties) {
        this.attackerRestClient = attackerRestClient;
        this.model = properties.attacker().model();
    }

    public Optional<String> identify(String outbound, List<Person> candidates) {
        Completion request = new Completion(model, List.of(new Message("user", prompt(outbound, candidates))), 0.0, MAX_TOKENS);
        JsonNode answer = attackerRestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        return chosen(answer == null ? "" : answer.path("choices").path(0).path("message").path("content").asString(), candidates);
    }

    // The last id that is really a candidate: a model may mention others while it explains.
    static Optional<String> chosen(String answer, List<Person> candidates) {
        Set<String> ids = candidates.stream().map(Person::id).collect(Collectors.toSet());
        String chosen = null;
        Matcher matcher = CANDIDATE.matcher(answer);
        while (matcher.find()) {
            if (ids.contains(matcher.group())) {
                chosen = matcher.group();
            }
        }
        return Optional.ofNullable(chosen);
    }

    static String prompt(String outbound, List<Person> candidates) {
        String people = candidates.stream()
                .map(person -> "%s | %s | born %s | %s | %s".formatted(person.id(), person.name(), person.birthDate(), person.town(), person.job()))
                .collect(Collectors.joining("\n"));
        return """
                You are testing a privacy filter. An AI provider received the message below. Some values in it may be masked, \
                replaced by placeholders, or replaced by fake but realistic values.

                Message:
                \"\"\"
                %s
                \"\"\"

                People:
                %s

                Which person is the message about? Use every clue: names, the date of birth inside a Bulgarian EGN (the first six \
                digits are YYMMDD, with 40 added to the month for births from 2000), the town and the job. Reply with one line: \
                the person's id, or NONE if you cannot tell.""".formatted(outbound, people);
    }

    record Completion(String model, List<Message> messages, double temperature, @JsonProperty("max_tokens") int maxTokens) {
    }

    record Message(String role, String content) {
    }
}
