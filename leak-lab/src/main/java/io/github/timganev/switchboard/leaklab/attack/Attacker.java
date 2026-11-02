package io.github.timganev.switchboard.leaklab.attack;

import io.github.timganev.switchboard.leaklab.dataset.Person;
import io.github.timganev.switchboard.leaklab.model.ModelClient;
import org.springframework.stereotype.Component;

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

    private final ModelClient attackerModel;

    public Attacker(ModelClient attackerModel) {
        this.attackerModel = attackerModel;
    }

    public Optional<String> identify(String outbound, List<Person> candidates) {
        return chosen(attackerModel.complete(prompt(outbound, candidates), MAX_TOKENS).text(), candidates);
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
}
