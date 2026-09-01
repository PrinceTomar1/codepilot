package com.codepilot.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordWithCodeRequest(
        @NotBlank @Email String email,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits") String code,
        @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String newPassword
) {
}
