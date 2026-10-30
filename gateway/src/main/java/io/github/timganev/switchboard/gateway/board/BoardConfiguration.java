package io.github.timganev.switchboard.gateway.board;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
class BoardConfiguration {

    @Bean
    @ConditionalOnProperty(name = "switchboard.board.type", havingValue = "redis")
    InflightBoard redisInflightBoard(StringRedisTemplate redis, @Value("${switchboard.board.lease-ttl}") Duration leaseTtl) {
        return new RedisInflightBoard(redis, leaseTtl, new LocalInflightBoard());
    }

    @Bean
    @ConditionalOnProperty(name = "switchboard.board.type", havingValue = "local", matchIfMissing = true)
    InflightBoard localInflightBoard() {
        return new LocalInflightBoard();
    }
}
