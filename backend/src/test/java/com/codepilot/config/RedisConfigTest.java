package com.codepilot.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real incident found via a live Redis-outage test: Spring Boot's default Lettuce
 * auto-configuration has no explicit command timeout, so it falls back to Lettuce's own
 * default -- 60 SECONDS. Every Redis-touching request (rate limiting, caching, idempotency, the
 * /actuator/health Redis indicator) hung for a full minute during an outage before the existing
 * fail-open try/catch logic even got a chance to run. This locks in the short timeout that
 * actually makes "fails open" true in practice, not just in theory.
 */
class RedisConfigTest {

    @Test
    void connectionFactoryUsesAShortCommandTimeoutNotLettucesSixtySecondDefault() {
        RedisProperties properties = new RedisProperties();
        properties.setHost("localhost");
        properties.setPort(6379);

        RedisConnectionFactory factory = new RedisConfig().redisConnectionFactory(properties);

        assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);
        Duration timeout = ((LettuceConnectionFactory) factory).getClientConfiguration().getCommandTimeout();
        assertThat(timeout).isEqualTo(Duration.ofSeconds(2));
        assertThat(timeout).isLessThan(Duration.ofSeconds(60));
    }

    @Test
    void connectionFactoryUsesTheConfiguredHostAndPort() {
        RedisProperties properties = new RedisProperties();
        properties.setHost("redis.internal");
        properties.setPort(6380);

        LettuceConnectionFactory factory =
                (LettuceConnectionFactory) new RedisConfig().redisConnectionFactory(properties);

        assertThat(factory.getHostName()).isEqualTo("redis.internal");
        assertThat(factory.getPort()).isEqualTo(6380);
    }
}
