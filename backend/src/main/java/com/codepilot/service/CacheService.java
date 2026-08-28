package com.codepilot.service;

import com.codepilot.dto.qa.AskResponse;
import com.codepilot.dto.repo.RepositoryDto;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin Redis-backed cache: repository detail responses and Q&A answers for identical questions.
 * Both are cached with a 10-minute TTL to cut repeated DB/LLM round-trips.
 */
@Service
@RequiredArgsConstructor
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);
    private static final Duration REPOSITORY_TTL = Duration.ofMinutes(10);
    private static final Duration QA_TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, Object> redisTemplate;

    public Optional<RepositoryDto> getRepository(UUID repositoryId) {
        return safeGet("repo:" + repositoryId, RepositoryDto.class);
    }

    public void putRepository(RepositoryDto dto) {
        safeSet("repo:" + dto.id(), dto, REPOSITORY_TTL);
    }

    public void evictRepository(UUID repositoryId) {
        safeDelete("repo:" + repositoryId);
    }

    public Optional<AskResponse> getQaAnswer(UUID repositoryId, String question) {
        return safeGet(qaKey(repositoryId, question), AskResponse.class);
    }

    public void putQaAnswer(UUID repositoryId, String question, AskResponse response) {
        safeSet(qaKey(repositoryId, question), response, QA_TTL);
    }

    private String qaKey(UUID repositoryId, String question) {
        return "qa:" + repositoryId + ":" + sha256(normalize(question));
    }

    private String normalize(String question) {
        return question == null ? "" : question.trim().toLowerCase(Locale.ROOT);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Optional<T> safeGet(String key, Class<T> type) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (type.isInstance(value)) {
                return Optional.of((T) value);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Redis GET failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void safeSet(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("Redis SET failed for key {}: {}", key, e.getMessage());
        }
    }

    private void safeDelete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis DELETE failed for key {}: {}", key, e.getMessage());
        }
    }
}
