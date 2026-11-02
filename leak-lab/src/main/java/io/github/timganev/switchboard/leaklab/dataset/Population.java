package io.github.timganev.switchboard.leaklab.dataset;

import io.github.timganev.switchboard.commons.privacy.Checksums;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Made-up people whose identifiers pass the same check digits as real ones; the same seed always gives the same people.
 */
public final class Population {

    private static final List<String> MEN = List.of("Petar", "Georgi", "Ivan", "Dimitar", "Nikolay", "Stefan", "Hristo", "Todor", "Martin", "Kaloyan");
    private static final List<String> WOMEN = List.of("Maria", "Elena", "Ivanka", "Desislava", "Nadezhda", "Gergana", "Radka", "Teodora", "Silvia", "Viktoria");
    private static final List<String> SURNAMES = List.of("Ivanov", "Petrov", "Georgiev", "Dimitrov", "Stoyanov", "Kolev", "Marinov", "Todorov", "Angelov", "Vasilev");
    private static final List<String> TOWNS = List.of("Sofia", "Plovdiv", "Varna", "Burgas", "Ruse", "Stara Zagora", "Pleven", "Veliko Tarnovo");
    private static final List<String> JOBS = List.of("nurse", "accountant", "teacher", "electrician", "pharmacist", "truck driver", "architect", "baker");
    private static final List<String> BANKS = List.of("BNBG", "UNCR", "STSA", "FINV", "RZBB");
    private static final LocalDate OLDEST = LocalDate.of(1955, 1, 1);
    private static final int BIRTH_SPAN_DAYS = 18_000;

    private Population() {
    }

    public static List<Person> generate(int size, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        List<Person> people = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            boolean woman = random.nextBoolean();
            String first = pick(woman ? WOMEN : MEN, random);
            String surname = pick(SURNAMES, random) + (woman ? "a" : "");
            LocalDate birth = OLDEST.plusDays(random.nextInt(BIRTH_SPAN_DAYS));
            people.add(new Person("P%02d".formatted(i), first + " " + surname, birth, pick(TOWNS, random), pick(JOBS, random),
                    egn(birth, woman, random), iban(random),
                    "+359 8%d %03d %04d".formatted(7 + random.nextInt(3), random.nextInt(1000), random.nextInt(10_000)),
                    "%s.%s%02d@example.bg".formatted(first, surname, random.nextInt(100)).toLowerCase(Locale.ROOT)));
        }
        return people;
    }

    // The ninth digit is even for a man and odd for a woman.
    private static String egn(LocalDate birth, boolean woman, SplittableRandom random) {
        int month = birth.getYear() >= 2000 ? birth.getMonthValue() + 40 : birth.getMonthValue();
        String digits = "%02d%02d%02d%02d%d".formatted(birth.getYear() % 100, month, birth.getDayOfMonth(),
                random.nextInt(100), 2 * random.nextInt(5) + (woman ? 1 : 0));
        return digits + Checksums.egnCheckDigit(digits);
    }

    private static String iban(SplittableRandom random) {
        String bban = pick(BANKS, random) + "%04d%02d%08d".formatted(random.nextInt(10_000), random.nextInt(100), random.nextInt(100_000_000));
        String compact = "BG" + Checksums.ibanCheckDigits("BG", bban) + bban;
        return String.join(" ", compact.split("(?<=\\G.{4})"));
    }

    private static String pick(List<String> values, SplittableRandom random) {
        return values.get(random.nextInt(values.size()));
    }
}
