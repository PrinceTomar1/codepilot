package com.codepilot.dto.error;

import java.time.Instant;

/** Standard error body shape returned by every non-2xx API response. */
public record ErrorResponse(Instant timestamp, int status, String error, String message, String path) {

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path);
    }
}
