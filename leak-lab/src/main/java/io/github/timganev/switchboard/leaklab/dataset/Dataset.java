package io.github.timganev.switchboard.leaklab.dataset;

import io.github.timganev.switchboard.commons.privacy.SensitiveType;

import java.util.ArrayList;
import java.util.List;

/**
 * One prompt per person, labelled as it is written; every other prompt leaves the name out, so only town, job and the EGN point to the person.
 */
public final class Dataset {

    private static final List<Template> TEMPLATES = List.of(
            new Template("the applicant", "Please review this loan application summary. Applicant: %1$s, EGN %2$s, works as a %3$s in %4$s. "
                    + "The salary is paid to IBAN %5$s. Is anything missing before we send it to the bank?",
                    List.of(SensitiveType.EGN, SensitiveType.IBAN)),
            new Template("the customer", "Draft a polite reply to a complaint. The customer, %1$s, a %3$s from %4$s (EGN %2$s), says a payment "
                    + "from IBAN %5$s was charged twice. They can be reached on %6$s.",
                    List.of(SensitiveType.EGN, SensitiveType.IBAN, SensitiveType.PHONE)),
            new Template("a new colleague", "Summarise this HR note in two sentences: %1$s (EGN %2$s) has joined our %4$s office as a %3$s. "
                    + "Personal email %7$s, mobile %6$s.",
                    List.of(SensitiveType.EGN, SensitiveType.EMAIL, SensitiveType.PHONE)),
            new Template("the patient", "Translate into Bulgarian: The patient, %1$s, EGN %2$s, a %3$s living in %4$s, missed the follow-up visit. "
                    + "Please call %6$s to book a new date.",
                    List.of(SensitiveType.EGN, SensitiveType.PHONE)));

    private Dataset() {
    }

    public static List<LeakCase> of(List<Person> population) {
        List<LeakCase> cases = new ArrayList<>();
        for (int i = 0; i < population.size(); i++) {
            Person person = population.get(i);
            Template template = TEMPLATES.get(i % TEMPLATES.size());
            boolean named = i % 2 == 0;
            String prompt = template.text().formatted(named ? person.name() : template.unnamed(), person.egn(), person.job(), person.town(),
                    person.iban(), person.phone(), person.email());
            List<Secret> secrets = template.types().stream().map(type -> new Secret(type, value(person, type))).toList();
            cases.add(new LeakCase(person, named, prompt, secrets));
        }
        return cases;
    }

    private static String value(Person person, SensitiveType type) {
        return switch (type) {
            case EGN -> person.egn();
            case IBAN -> person.iban();
            case PHONE -> person.phone();
            case EMAIL -> person.email();
            default -> throw new IllegalArgumentException("No " + type + " in the dataset");
        };
    }

    private record Template(String unnamed, String text, List<SensitiveType> types) {
    }
}
