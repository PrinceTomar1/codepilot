package com.codepilot.security;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed fixed-window rate limiter (atomic INCR, TTL set on the first hit in each
 * window). Fails open if Redis is unreachable, so a cache outage degrades to "allow everything"
 * rather than taking the API down.
 */
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final RedisTemplate<String, Object> redisTemplate;

    /** Returns true if this call is within {@code limit} for the current window, false if it should be rejected. */
    public boolean tryConsume(String key, int limit, Duration window) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, window);
            }
            return count == null || count <= limit;
        } catch (Exception e) {
            log.warn("Rate limit check failed for key {} (Redis unavailable?): {}", key, e.getMessage());
            return true;
        }
    }
}
