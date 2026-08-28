package com.codepilot.dto.repo;

import jakarta.validation.constraints.NotBlank;

public record CreateRepositoryFromGitHubRequest(
        @NotBlank String githubOwner,
        @NotBlank String githubRepo
) {
}
