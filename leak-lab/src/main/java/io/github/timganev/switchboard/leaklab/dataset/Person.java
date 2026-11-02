package io.github.timganev.switchboard.leaklab.dataset;

import java.time.LocalDate;

public record Person(String id, String name, LocalDate birthDate, String town, String job, String egn, String iban, String phone, String email) {
}
