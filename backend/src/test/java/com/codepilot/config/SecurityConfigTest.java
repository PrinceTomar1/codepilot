package com.codepilot.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real bug: CORS_ALLOWED_ORIGIN only ever held a single origin, so switching the frontend's
 * hosting/domain meant the moment the backend picked up the new value, anyone still on the old
 * URL (a bookmark, a stale tab, a DNS resolver that hasn't picked up a brand-new custom domain
 * yet) got hard CORS failures with no transition window. These tests lock in that
 * corsConfigurationSource() now accepts a comma-separated list, so an old and a new origin can be
 * accepted simultaneously during a migration.
 */
class SecurityConfigTest {

    private SecurityConfig configWithOrigins(String rawValue) {
        // corsConfigurationSource() never touches the filter/UserDetailsService fields, only
        // `env` -- passing null for the rest avoids mocking concrete filter classes (which pulls
        // in Mockito's inline byte-buddy agent and is fragile across JDK versions) for something
        // this test doesn't exercise.
        Environment env = mock(Environment.class);
        when(env.getProperty("app.cors.allowed-origin", "http://localhost:5173")).thenReturn(rawValue);
        return new SecurityConfig(null, null, null, null, env);
    }

    private CorsConfiguration resolvedConfig(SecurityConfig config) {
        CorsConfigurationSource source = config.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/auth/login");
        return source.getCorsConfiguration(request);
    }

    @Test
    void singleOriginStillWorksUnchanged() {
        CorsConfiguration resolved = resolvedConfig(configWithOrigins("https://codepilot-ai.up.railway.app"));

        assertThat(resolved.getAllowedOrigins()).containsExactly("https://codepilot-ai.up.railway.app");
    }

    @Test
    void commaSeparatedOriginsAreAllAccepted() {
        CorsConfiguration resolved = resolvedConfig(
                configWithOrigins("https://old-domain.up.railway.app,https://codepilot-ai.up.railway.app"));

        assertThat(resolved.getAllowedOrigins()).containsExactlyInAnyOrder(
                "https://old-domain.up.railway.app", "https://codepilot-ai.up.railway.app");
    }

    @Test
    void toleratesWhitespaceAroundCommas() {
        CorsConfiguration resolved = resolvedConfig(
                configWithOrigins(" https://old-domain.up.railway.app , https://codepilot-ai.up.railway.app "));

        assertThat(resolved.getAllowedOrigins()).containsExactlyInAnyOrder(
                "https://old-domain.up.railway.app", "https://codepilot-ai.up.railway.app");
    }
}
