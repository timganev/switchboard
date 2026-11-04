package io.github.timganev.switchboard.gateway.auth;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, checks and revokes the teams' API keys; only a SHA-256 hash of each key is stored.
 */
public class ApiKeys {

    // A recognisable prefix lets the guardian and secret scanners catch a key pasted where it does not belong.
    static final String PREFIX = "sb-";
    private static final int KEY_BYTES = 32;
    private static final int SHOWN_LENGTH = 10;
    private static final int MAX_TEAM_LENGTH = 200;
    // Writing the last use once a minute keeps a busy key from turning every request into a write.
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ApiKeys(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public IssuedKey issue(String team) {
        if (team == null || team.isBlank() || team.strip().length() > MAX_TEAM_LENGTH) {
            throw new IllegalArgumentException("A key needs a team of 1 to " + MAX_TEAM_LENGTH + " characters");
        }
        byte[] bytes = new byte[KEY_BYTES];
        random.nextBytes(bytes);
        String key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        IssuedKey issued = new IssuedKey(UUID.randomUUID().toString(), team.strip(), key, now());
        jdbc.sql("INSERT INTO api_key (id, team, key_hash, key_prefix, created_at) VALUES (:id, :team, :hash, :prefix, :createdAt)")
                .param("id", issued.id())
                .param("team", issued.team())
                .param("hash", hash(key))
                .param("prefix", key.substring(0, SHOWN_LENGTH))
                .param("createdAt", issued.createdAt().atOffset(ZoneOffset.UTC))
                .update();
        return issued;
    }

    // Found by its hash, so the lookup never compares the secret itself.
    public Optional<String> team(String key) {
        Optional<Match> match = jdbc.sql("SELECT id, team, last_used_at FROM api_key WHERE key_hash = :hash AND revoked_at IS NULL")
                .param("hash", hash(key))
                .query(Match.class)
                .optional();
        match.ifPresent(found -> {
            Instant now = now();
            if (found.lastUsedAt() == null || found.lastUsedAt().toInstant().isBefore(now.minus(TOUCH_EVERY))) {
                jdbc.sql("UPDATE api_key SET last_used_at = :now WHERE id = :id")
                        .param("now", now.atOffset(ZoneOffset.UTC))
                        .param("id", found.id())
                        .update();
            }
        });
        return match.map(Match::team);
    }

    public List<KeyInfo> list(String team) {
        return jdbc.sql("""
                        SELECT id, team, key_prefix, created_at, last_used_at, revoked_at FROM api_key
                        WHERE team = :team ORDER BY created_at""")
                .param("team", team)
                .query((row, number) -> new KeyInfo(row.getString("id"), row.getString("team"), row.getString("key_prefix"),
                        instant(row.getObject("created_at", OffsetDateTime.class)),
                        instant(row.getObject("last_used_at", OffsetDateTime.class)),
                        instant(row.getObject("revoked_at", OffsetDateTime.class))))
                .list();
    }

    public boolean revoke(String id) {
        return jdbc.sql("UPDATE api_key SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                .param("now", now().atOffset(ZoneOffset.UTC))
                .param("id", id)
                .update() == 1;
    }

    // The keys are random, unlike passwords, so a fast hash is enough: there is nothing to guess from a word list.
    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    public record IssuedKey(String id, String team, String key, Instant createdAt) {
    }

    public record KeyInfo(String id, String team, String prefix, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
    }

    private record Match(String id, String team, OffsetDateTime lastUsedAt) {
    }
}
