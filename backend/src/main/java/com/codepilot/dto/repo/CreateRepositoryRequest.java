package com.codepilot.dto.repo;

import jakarta.validation.constraints.NotBlank;

public record CreateRepositoryRequest(
        @NotBlank String githubOwner,
        @NotBlank String githubRepo,
        @NotBlank String accessToken
) {
}
