package com.codepilot.security;

import com.codepilot.dto.error.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Protects the expensive/abusable endpoints (AI calls, repository connect+index, inbound
 * webhooks) with a per-identity fixed-window rate limit backed by Redis. Everything else
 * passes through untouched.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Pattern AI_ENDPOINT = Pattern.compile("^/api/repositories/[^/]+/(ask|onboarding)$");
    private static final Pattern SEARCH_ENDPOINT = Pattern.compile("^/api/repositories/[^/]+/search$");

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limit.ai.limit:20}")
    private int aiLimit;

    @Value("${app.rate-limit.ai.window-seconds:60}")
    private int aiWindowSeconds;

    // More generous than the "ai" bucket: search never touches the LLM (no scarce Gemini quota
    // to protect), just retrieval -- but it's still a real DB+embedding call worth bounding.
    @Value("${app.rate-limit.search.limit:60}")
    private int searchLimit;

    @Value("${app.rate-limit.search.window-seconds:60}")
    private int searchWindowSeconds;

    @Value("${app.rate-limit.indexing.limit:10}")
    private int indexingLimit;

    @Value("${app.rate-limit.indexing.window-seconds:3600}")
    private int indexingWindowSeconds;

    @Value("${app.rate-limit.webhook.limit:120}")
    private int webhookLimit;

    @Value("${app.rate-limit.webhook.window-seconds:60}")
    private int webhookWindowSeconds;

    // A 6-digit verification code has only ~1M possibilities -- without a tight limit here this
    // endpoint would be a straightforward brute-force target.
    @Value("${app.rate-limit.verify-code.limit:10}")
    private int verifyCodeLimit;

    @Value("${app.rate-limit.verify-code.window-seconds:900}")
    private int verifyCodeWindowSeconds;

    // An unauthenticated endpoint that emails someone needs its own guard against becoming a
    // free inbox-spam tool.
    @Value("${app.rate-limit.login-otp-request.limit:5}")
    private int loginOtpRequestLimit;

    @Value("${app.rate-limit.login-otp-request.window-seconds:3600}")
    private int loginOtpRequestWindowSeconds;

    // Same reasoning as verify-code: a 6-digit code is only ~1M possibilities, and this one
    // grants a live session on success, so it needs at least as tight a brute-force guard.
    @Value("${app.rate-limit.login-otp-verify.limit:10}")
    private int loginOtpVerifyLimit;

    @Value("${app.rate-limit.login-otp-verify.window-seconds:900}")
    private int loginOtpVerifyWindowSeconds;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    private record Bucket(String name, int limit, Duration window) {
    }

    private Bucket classify(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        if ("POST".equals(method) && AI_ENDPOINT.matcher(path).matches()) {
            return new Bucket("ai", aiLimit, Duration.ofSeconds(aiWindowSeconds));
        }
        if ("POST".equals(method) && SEARCH_ENDPOINT.matcher(path).matches()) {
            return new Bucket("search", searchLimit, Duration.ofSeconds(searchWindowSeconds));
        }
        if ("POST".equals(method) && "/api/repositories".equals(path)) {
            return new Bucket("indexing", indexingLimit, Duration.ofSeconds(indexingWindowSeconds));
        }
        if ("POST".equals(method) && "/api/webhooks/github".equals(path)) {
            return new Bucket("webhook", webhookLimit, Duration.ofSeconds(webhookWindowSeconds));
        }
        if ("POST".equals(method) && "/api/auth/verify-code".equals(path)) {
            return new Bucket("verify-code", verifyCodeLimit, Duration.ofSeconds(verifyCodeWindowSeconds));
        }
        if ("POST".equals(method) && "/api/auth/login-otp/request".equals(path)) {
            return new Bucket("login-otp-request", loginOtpRequestLimit, Duration.ofSeconds(loginOtpRequestWindowSeconds));
        }
        if ("POST".equals(method) && "/api/auth/login-otp/verify".equals(path)) {
            return new Bucket("login-otp-verify", loginOtpVerifyLimit, Duration.ofSeconds(loginOtpVerifyWindowSeconds));
        }
        // Shares the login-otp-verify bucket/limits -- it's the same brute-forceable 6-digit
        // code, just spent on a password change instead of a sign-in, so it needs at least as
        // tight a guard.
        if ("POST".equals(method) && "/api/auth/reset-password".equals(path)) {
            return new Bucket("login-otp-verify", loginOtpVerifyLimit, Duration.ofSeconds(loginOtpVerifyWindowSeconds));
        }
        return null;
    }

    private String resolveIdentity(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return "user:" + principal.getId();
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String remote = (forwardedFor != null && !forwardedFor.isBlank())
                ? forwardedFor.split(",")[0].trim()
                : request.getRemoteAddr();
        return "ip:" + remote;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        Bucket bucket = classify(request);
        if (bucket == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = "ratelimit:" + bucket.name() + ":" + resolveIdentity(request);
        boolean allowed = rateLimiter.tryConsume(key, bucket.limit(), bucket.window());
        if (!allowed) {
            writeTooManyRequests(response, request);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeTooManyRequests(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests",
                "Rate limit exceeded. Please slow down and try again shortly.",
                request.getRequestURI());

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
