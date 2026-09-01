package com.codepilot.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginCodeRequest(
        @NotBlank @Email String email
) {
}
