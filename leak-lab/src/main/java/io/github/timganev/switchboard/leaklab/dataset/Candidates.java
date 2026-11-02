package io.github.timganev.switchboard.leaklab.dataset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The people the attacker chooses from: the right one among those most like them, as if the attacker had already narrowed the search.
 */
public final class Candidates {

    private Candidates() {
    }

    public static List<Person> of(Person target, List<Person> population, int size, long seed) {
        Random random = new Random(seed ^ target.id().hashCode());
        List<Person> others = new ArrayList<>(population.stream().filter(person -> !person.id().equals(target.id())).toList());
        Collections.shuffle(others, random);
        others.sort(Comparator.comparingInt((Person person) -> similarity(person, target)).reversed());
        List<Person> candidates = new ArrayList<>(others.subList(0, Math.min(size - 1, others.size())));
        candidates.add(target);
        Collections.shuffle(candidates, random);
        return candidates;
    }

    private static int similarity(Person person, Person target) {
        return (person.town().equals(target.town()) ? 1 : 0) + (person.job().equals(target.job()) ? 1 : 0);
    }
}
