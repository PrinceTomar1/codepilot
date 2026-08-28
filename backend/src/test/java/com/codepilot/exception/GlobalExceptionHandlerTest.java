package com.codepilot.exception;

import com.codepilot.dto.error.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void dataIntegrityViolationMapsTo409WithoutLeakingRawDbMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/register");
        // Real DB exceptions carry verbose driver-specific text (constraint names, SQL state) --
        // that must not reach the client.
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"users_email_key\"");

        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(body.path()).isEqualTo("/api/auth/register");
        assertThat(body.message()).doesNotContain("constraint").doesNotContain("SQL");
    }

    @Test
    void noResourceFoundMapsTo404NotInternalServerError() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/env");
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "actuator/env");

        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(body.path()).isEqualTo("/actuator/env");
    }

    @Test
    void malformedRequestBodyMapsTo400NotInternalServerError() {
        // A real incident found during a full-project audit: malformed JSON was falling through
        // to the generic 500 handler instead of being reported as the client error it actually is.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/register");
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character", (org.springframework.http.HttpInputMessage) null);

        ResponseEntity<ErrorResponse> response = handler.handleMalformedRequestBody(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(body.path()).isEqualTo("/api/auth/register");
        // Must not leak the raw Jackson parser message (internal detail, and could be verbose).
        assertThat(body.message()).doesNotContain("Unexpected character");
    }
}
