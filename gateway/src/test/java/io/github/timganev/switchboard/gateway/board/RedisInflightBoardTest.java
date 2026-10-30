package io.github.timganev.switchboard.gateway.board;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        RedisInflightBoard replica1 = board(redis, Duration.ofMinutes(1), new LocalInflightBoard());
        RedisInflightBoard replica2 = board(redis, Duration.ofMinutes(1), new LocalInflightBoard());

        Lease first = replica1.acquire(List.of("a", "b"));
        Lease second = replica2.acquire(List.of("a", "b"));
        Lease third = replica1.acquire(List.of("a", "b"));

        assertThat(List.of(first.instance(), second.instance(), third.instance())).containsExactly("a", "b", "a");
        assertThat(count("a")).isEqualTo(2);
    }

    @Test
    void shouldReleaseTheLeaseWhenClosed() {
        RedisInflightBoard board = board(redis, Duration.ofMinutes(1), new LocalInflightBoard());

        Lease lease = board.acquire(List.of("a", "b"));
        lease.close();

        assertThat(count("a")).isZero();
        assertThat(board.acquire(List.of("a", "b")).instance()).isEqualTo("a");
    }

    @Test
    void shouldForgetALeaseThatWasNeverClosed() throws InterruptedException {
        RedisInflightBoard board = board(redis, Duration.ofMillis(200), new LocalInflightBoard());
        board.acquire(List.of("a", "b"));

        Thread.sleep(300);

        assertThat(board.acquire(List.of("a", "b")).instance()).isEqualTo("a");
    }

    @Test
    void shouldCountLocallyWhenRedisIsUnreachable() {
        LettuceConnectionFactory deadFactory = factory("localhost", 1);
        try {
            LocalInflightBoard fallback = new LocalInflightBoard();
            RedisInflightBoard board = board(new StringRedisTemplate(deadFactory), Duration.ofMinutes(1), fallback);

            Lease lease = board.acquire(List.of("a", "b"));

            assertThat(lease.instance()).isEqualTo("a");
            assertThat(fallback.count("a")).isEqualTo(1);
        } finally {
            deadFactory.destroy();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldStopAskingRedisWhileItKeepsFailingAndTryAgainLater() throws InterruptedException {
        StringRedisTemplate failing = mock(StringRedisTemplate.class);
        when(failing.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));
        LocalInflightBoard fallback = new LocalInflightBoard();
        RedisInflightBoard board = new RedisInflightBoard(failing, Duration.ofMinutes(1), fallback,
                RedisInflightBoard.circuitBreaker(Duration.ofMillis(200)));

        for (int i = 0; i < 10; i++) {
            board.acquire(List.of("a")).close();
        }
        verify(failing, times(3)).execute(any(RedisScript.class), anyList(), any(), any());

        Thread.sleep(300);
        board.acquire(List.of("a")).close();

        verify(failing, times(4)).execute(any(RedisScript.class), anyList(), any(), any());
        assertThat(fallback.count("a")).isZero();
    }

    private static long count(String instance) {
        return redis.opsForZSet().zCard(RedisInflightBoard.KEY_PREFIX + instance);
    }

    private static RedisInflightBoard board(StringRedisTemplate template, Duration leaseTtl, InflightBoard fallback) {
        return new RedisInflightBoard(template, leaseTtl, fallback, RedisInflightBoard.circuitBreaker(Duration.ofSeconds(10)));
    }

    private static LettuceConnectionFactory factory(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }
}
