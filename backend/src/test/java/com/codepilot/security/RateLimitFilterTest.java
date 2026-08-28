package com.codepilot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private RateLimiter rateLimiter;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        // Mirror Spring Boot's auto-configured ObjectMapper, which registers jackson-datatype-jsr310
        // (on the classpath) so java.time.Instant serializes -- a bare `new ObjectMapper()` doesn't.
        filter = new RateLimitFilter(rateLimiter, new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "aiLimit", 20);
        ReflectionTestUtils.setField(filter, "aiWindowSeconds", 60);
        ReflectionTestUtils.setField(filter, "indexingLimit", 10);
        ReflectionTestUtils.setField(filter, "indexingWindowSeconds", 3600);
        ReflectionTestUtils.setField(filter, "webhookLimit", 120);
        ReflectionTestUtils.setField(filter, "webhookWindowSeconds", 60);
        ReflectionTestUtils.setField(filter, "verifyCodeLimit", 10);
        ReflectionTestUtils.setField(filter, "verifyCodeWindowSeconds", 900);
        ReflectionTestUtils.setField(filter, "searchLimit", 60);
        ReflectionTestUtils.setField(filter, "searchWindowSeconds", 60);
    }

    @Test
    void nonMatchingPathBypassesRateLimitEntirely() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repositories");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any());
    }

    @Test
    void allowsRequestUnderLimit() throws Exception {
        when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/repositories/abc-123/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200); // MockHttpServletResponse defaults to 200
    }

    @Test
    void rejectsRequestOverLimitWith429AndErrorBody() throws Exception {
        when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/repositories/abc-123/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("\"status\":429").contains("Too Many Requests");
    }

    @Test
    void usesAiBucketKeyForAskEndpoint() throws Exception {
        when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/repositories/repo-1/ask");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(rateLimiter).tryConsume(
                org.mockito.ArgumentMatchers.contains("ratelimit:ai:"), org.mockito.ArgumentMatchers.eq(20), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(60)));
    }

    @Test
    void usesIndexingBucketKeyForRepositoryCreate() throws Exception {
        when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/repositories");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(rateLimiter).tryConsume(
                org.mockito.ArgumentMatchers.contains("ratelimit:indexing:"), org.mockito.ArgumentMatchers.eq(10), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(3600)));
    }

    @Test
    void usesVerifyCodeBucketKeyForVerifyCodeEndpoint() throws Exception {
        // A 6-digit code is only ~1M possibilities -- this bucket exists specifically to make
        // brute-forcing it impractical, so the wiring matters more than usual here.
        when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/verify-code");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(rateLimiter).tryConsume(
                org.mockito.ArgumentMatchers.contains("ratelimit:verify-code:"),
                org.mockito.ArgumentMatchers.eq(10), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(900)));
    }

    @Test
    void usesSearchBucketNotTheStricterAiBucketForSearchEndpoint() throws Exception {
        // Search never calls the LLM (no scarce Gemini quota at stake), so it must use its own
        // more generous bucket rather than accidentally sharing -- or being blocked by -- the
        // "ai" bucket tuned specifically for LLM-calling endpoints.
        when(rateLimiter.tryConsume(anyString(), anyInt(), any())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/repositories/repo-1/search");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(rateLimiter).tryConsume(
                org.mockito.ArgumentMatchers.contains("ratelimit:search:"),
                org.mockito.ArgumentMatchers.eq(60), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(60)));
    }

    @Test
    void disabledFilterAlwaysBypasses() throws Exception {
        ReflectionTestUtils.setField(filter, "enabled", false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/repositories/repo-1/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any());
    }
}
