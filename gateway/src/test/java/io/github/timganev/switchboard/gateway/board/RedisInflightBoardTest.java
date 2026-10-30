package io.github.timganev.switchboard.gateway.board;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisInflightBoardTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connectionFactory = factory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void clean() {
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void shouldShareTheCountsBetweenReplicas() {
        RedisInflightBoard replica1 = new RedisInflightBoard(redis, Duration.ofMinutes(1), new LocalInflightBoard());
        RedisInflightBoard replica2 = new RedisInflightBoard(redis, Duration.ofMinutes(1), new LocalInflightBoard());

        Lease first = replica1.acquire(List.of("a", "b"));
        Lease second = replica2.acquire(List.of("a", "b"));
        Lease third = replica1.acquire(List.of("a", "b"));

        assertThat(List.of(first.instance(), second.instance(), third.instance())).containsExactly("a", "b", "a");
        assertThat(count("a")).isEqualTo(2);
    }

    @Test
    void shouldReleaseTheLeaseWhenClosed() {
        RedisInflightBoard board = new RedisInflightBoard(redis, Duration.ofMinutes(1), new LocalInflightBoard());

        Lease lease = board.acquire(List.of("a", "b"));
        lease.close();

        assertThat(count("a")).isZero();
        assertThat(board.acquire(List.of("a", "b")).instance()).isEqualTo("a");
    }

    @Test
    void shouldForgetALeaseThatWasNeverClosed() throws InterruptedException {
        RedisInflightBoard board = new RedisInflightBoard(redis, Duration.ofMillis(200), new LocalInflightBoard());
        board.acquire(List.of("a", "b"));

        Thread.sleep(300);

        assertThat(board.acquire(List.of("a", "b")).instance()).isEqualTo("a");
    }

    @Test
    void shouldCountLocallyWhenRedisIsUnreachable() {
        LettuceConnectionFactory deadFactory = factory("localhost", 1);
        try {
            LocalInflightBoard fallback = new LocalInflightBoard();
            RedisInflightBoard board = new RedisInflightBoard(new StringRedisTemplate(deadFactory), Duration.ofMinutes(1), fallback);

            Lease lease = board.acquire(List.of("a", "b"));

            assertThat(lease.instance()).isEqualTo("a");
            assertThat(fallback.count("a")).isEqualTo(1);
        } finally {
            deadFactory.destroy();
        }
    }

    private static long count(String instance) {
        return redis.opsForZSet().zCard(RedisInflightBoard.KEY_PREFIX + instance);
    }

    private static LettuceConnectionFactory factory(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }
}
