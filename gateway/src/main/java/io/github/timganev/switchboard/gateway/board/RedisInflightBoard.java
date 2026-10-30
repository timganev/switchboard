package io.github.timganev.switchboard.gateway.board;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The board shared by all gateway replicas: a Lua script picks the least busy instance and counts the request in one atomic step.
 * Each request is a lease with an expiry, so a replica that dies mid-request does not leave its count behind.
 * While Redis keeps failing, a circuit breaker skips it for a while, so requests do not each wait for a timeout.
 */
public class RedisInflightBoard implements InflightBoard {

    private static final Logger log = LoggerFactory.getLogger(RedisInflightBoard.class);
    // The hash tag puts every key in one cluster slot, so one script may touch them all.
    static final String KEY_PREFIX = "{switchboard}:inflight:";

    private final StringRedisTemplate redis;
    private final Duration leaseTtl;
    private final InflightBoard fallback;
    private final CircuitBreaker circuitBreaker;
    private final RedisScript<String> acquireScript = RedisScript.of(new ClassPathResource("board/acquire.lua"), String.class);

    public RedisInflightBoard(StringRedisTemplate redis, Duration leaseTtl, InflightBoard fallback, CircuitBreaker circuitBreaker) {
        this.redis = redis;
        this.leaseTtl = leaseTtl;
        this.fallback = fallback;
        this.circuitBreaker = circuitBreaker;
    }

    public static CircuitBreaker circuitBreaker(Duration retryAfter) {
        CircuitBreaker circuitBreaker = CircuitBreaker.of("redis-board", CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .waitDurationInOpenState(retryAfter)
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(DataAccessException.class)
                .build());
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("Redis board circuit breaker: {}", event.getStateTransition()));
        return circuitBreaker;
    }

    @Override
    public Lease acquire(List<String> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No candidates to choose from");
        }
        String leaseId = UUID.randomUUID().toString();
        String key;
        try {
            key = circuitBreaker.executeSupplier(() -> redis.execute(acquireScript,
                    candidates.stream().map(candidate -> KEY_PREFIX + candidate).toList(), leaseId, String.valueOf(leaseTtl.toMillis())));
        } catch (CallNotPermittedException exception) {
            return fallback.acquire(candidates);
        } catch (DataAccessException exception) {
            log.warn("Redis board unavailable, counting this replica only: {}", exception.getMostSpecificCause().toString());
            return fallback.acquire(candidates);
        }
        String instance = key.substring(KEY_PREFIX.length());
        AtomicBoolean closed = new AtomicBoolean();
        return new Lease() {
            @Override
            public String instance() {
                return instance;
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    release(key, leaseId);
                }
            }
        };
    }

    private void release(String key, String leaseId) {
        try {
            circuitBreaker.executeRunnable(() -> redis.opsForZSet().remove(key, leaseId));
        } catch (CallNotPermittedException exception) {
            log.debug("Redis board skipped, lease on {} expires on its own", key);
        } catch (DataAccessException exception) {
            log.warn("Could not release lease on {}, it expires on its own: {}", key, exception.getMostSpecificCause().toString());
        }
    }
}
