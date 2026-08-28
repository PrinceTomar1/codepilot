package com.codepilot.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed "have we seen this key before" check (atomic SETNX + TTL), used to make
 * webhook processing idempotent against GitHub's at-least-once delivery/redelivery semantics.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Atomically marks {@code key} as seen. Returns true the first time a given key is
     * passed in (caller should proceed), false on every subsequent call within the TTL
     * window (caller should treat it as a duplicate and skip work).
     *
     * <p>Fails open (returns true) if Redis is unreachable, so a cache outage degrades to
     * "process everything" rather than silently dropping real webhook deliveries.
     */
    public boolean markFirstSeen(String key, Duration ttl) {
        try {
            Boolean firstSeen = redisTemplate.opsForValue().setIfAbsent(key, Boolean.TRUE, ttl);
            return Boolean.TRUE.equals(firstSeen);
        } catch (Exception e) {
            log.warn("Idempotency check failed for key {} (Redis unavailable?): {}", key, e.getMessage());
            return true;
        }
    }
}
