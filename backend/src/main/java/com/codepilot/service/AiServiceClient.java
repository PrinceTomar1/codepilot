package com.codepilot.service;

import com.codepilot.dto.ai.AiArchitectureRequest;
import com.codepilot.dto.ai.AiArchitectureResponse;
import com.codepilot.dto.ai.AiIndexRequest;
import com.codepilot.dto.ai.AiIndexResponse;
import com.codepilot.dto.ai.AiOnboardingRequest;
import com.codepilot.dto.ai.AiOnboardingResponse;
import com.codepilot.dto.ai.AiQueryRequest;
import com.codepilot.dto.ai.AiQueryResponse;
import com.codepilot.dto.ai.AiReviewRequest;
import com.codepilot.dto.ai.AiReviewResponse;
import com.codepilot.dto.ai.AiSearchRequest;
import com.codepilot.dto.ai.AiSearchResponse;
import com.codepilot.exception.AiServiceException;
import com.codepilot.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Client for the downstream Python AI service (RAG indexing/Q&A, PR review, onboarding docs).
 * Base URL is configured via the AI_SERVICE_URL env var. Calls are made reactively but blocked on,
 * since the callers (request handlers / async jobs) are synchronous.
 */
@Service
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final WebClient aiServiceWebClient;
    private final WebClient aiServiceReviewWebClient;
    private final ObjectMapper objectMapper;

    public AiServiceClient(
            @Qualifier("aiServiceWebClient") WebClient aiServiceWebClient,
            @Qualifier("aiServiceReviewWebClient") WebClient aiServiceReviewWebClient,
            ObjectMapper objectMapper) {
        this.aiServiceWebClient = aiServiceWebClient;
        this.aiServiceReviewWebClient = aiServiceReviewWebClient;
        this.objectMapper = objectMapper;
    }

    public AiIndexResponse index(AiIndexRequest request) {
        return post("/index", request, AiIndexResponse.class);
    }

    public AiQueryResponse query(AiQueryRequest request) {
        return post("/query", request, AiQueryResponse.class);
    }

    public AiReviewResponse review(AiReviewRequest request) {
        return post(aiServiceReviewWebClient, "/review", request, AiReviewResponse.class);
    }

    public AiOnboardingResponse onboarding(AiOnboardingRequest request) {
        return post("/onboarding", request, AiOnboardingResponse.class);
    }

    public AiArchitectureResponse architecture(AiArchitectureRequest request) {
        return post("/architecture", request, AiArchitectureResponse.class);
    }

    public AiSearchResponse search(AiSearchRequest request) {
        return post("/search", request, AiSearchResponse.class);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        return post(aiServiceWebClient, path, body, responseType);
    }

    private <T> T post(WebClient client, String path, Object body, Class<T> responseType) {
        try {
            return client.post()
                    .uri(path)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(responseType)
                    .retryWhen(retrySpec())
                    .block();
        } catch (Exception raw) {
            // When Retry.backoff() exhausts its attempts, Reactor rethrows a RetryExhaustedException
            // whose own message is just "Retries exhausted: N/N" -- it wraps the *real* last-attempt
            // exception as the cause. Unwrap it so callers see the actual failure (e.g. "LLM not
            // configured") instead of a meaningless retry-bookkeeping message.
            Exception e = Exceptions.isRetryExhausted(raw) && raw.getCause() instanceof Exception cause
                    ? cause : raw;

            if (e instanceof WebClientResponseException wcre) {
                String responseBody = wcre.getResponseBodyAsString();
                log.error("AI service call to {} failed with status {}: {}", path, wcre.getStatusCode(), responseBody);

                String detail = extractErrorDetail(responseBody);
                if (wcre.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
                    // ai-service's deliberate "not configured" signal (see LLMNotConfiguredError) --
                    // surface it as-is (503, real message) rather than flattening it into a generic
                    // 502, so the frontend can show the user something actionable.
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, detail != null ? detail : "AI features are not configured yet.", wcre);
                }
                if (wcre.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    // ai-service's deliberate signal that the LLM provider itself is rate-limited /
                    // over quota (see LLMRateLimitedError) -- surface as 429 with the real message
                    // (e.g. "retry in 33s") instead of a generic 502 that gives the user no idea why.
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, detail != null ? detail : "AI provider rate limit reached. Please try again shortly.", wcre);
                }
                throw new AiServiceException(
                        "AI service call to " + path + " failed" + (detail != null ? ": " + detail : ": " + wcre.getStatusCode()),
                        wcre);
            }
            log.error("AI service call to {} failed", path, e);
            throw new AiServiceException("AI service call to " + path + " failed: " + e.getMessage(), e);
        }
    }

    /** Pulls the "error" field out of ai-service's {"error": "..."} JSON error body, if present. */
    private String extractErrorDetail(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            JsonNode error = node.get("error");
            return error != null && error.isTextual() ? error.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Retries transient failures only -- connection/timeout errors and 5xx EXCEPT 503, never 4xx.
     * A bad request or malformed payload fails identically on every retry, so retrying wastes time.
     * 503 is excluded too: this app's ai-service deliberately returns 503 for "LLM not configured"
     * (see LLMNotConfiguredError in ai-service/app/services/llm.py) -- that's a stable condition
     * that won't resolve itself in a 2-second backoff window, so retrying it just adds latency
     * without changing the outcome. Two retries with a short backoff is enough to ride out a brief
     * network blip without meaningfully slowing down the (already backgrounded/async) callers.
     */
    static Retry retrySpec() {
        return Retry.backoff(2, Duration.ofMillis(300))
                .maxBackoff(Duration.ofSeconds(2))
                .filter(AiServiceClient::isRetryable);
    }

    private static boolean isRetryable(Throwable ex) {
        if (ex instanceof WebClientResponseException wcre) {
            return wcre.getStatusCode().is5xxServerError() && wcre.getStatusCode() != HttpStatus.SERVICE_UNAVAILABLE;
        }
        return true;
    }
}
