package com.codepilot.exception;

import org.springframework.http.HttpStatus;

/** Raised when a call to the downstream Python AI service fails or times out. */
public class AiServiceException extends ApiException {
    public AiServiceException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
