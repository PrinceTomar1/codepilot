package com.codepilot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without this entry point, Spring Security's default Http403ForbiddenEntryPoint returns 403 for
 * missing/invalid/expired auth -- but the frontend only auto-recovers (clears a stale session,
 * redirects to /login) on 401. A 403 here left users stuck on a confusing raw error with no way
 * out except manually clearing browser storage.
 */
class RestAuthenticationEntryPointTest {

    @Test
    void commenceReturns401WithStandardErrorShape() throws Exception {
        RestAuthenticationEntryPoint entryPoint =
                new RestAuthenticationEntryPoint(new ObjectMapper().findAndRegisterModules());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repositories");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("invalid token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("\"status\":401")
                .contains("Unauthorized")
                .contains("/api/repositories");
    }
}
