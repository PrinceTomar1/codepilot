package com.codepilot.dto.repo;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record RepositoryDto(
        UUID id,
        String githubOwner,
        String githubRepo,
        String defaultBranch,
        String status,
        Instant indexedAt,
        Instant createdAt,
        String lastIndexError
) implements Serializable {
}
