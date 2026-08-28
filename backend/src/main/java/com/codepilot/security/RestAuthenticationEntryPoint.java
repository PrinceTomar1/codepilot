package com.codepilot.security;

import com.codepilot.dto.error.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Without an explicit AuthenticationEntryPoint, Spring Security falls back to
 * Http403ForbiddenEntryPoint for any request that fails authentication (missing/invalid/expired
 * JWT) -- a 403 instead of the correct 401. That's not just a REST-convention nitpick: the
 * frontend's axios interceptor only auto-clears a stale session and redirects to /login on 401
 * (see frontend/src/api/client.ts), so a 403 here left users stuck on a confusing raw error with
 * no automatic recovery path once their token went stale (e.g. after a JWT_SECRET rotation).
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ErrorResponse body = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                "Authentication required. Please sign in again.",
                request.getRequestURI());

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
