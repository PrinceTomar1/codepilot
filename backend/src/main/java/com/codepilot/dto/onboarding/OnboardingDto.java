package com.codepilot.dto.onboarding;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record OnboardingDto(
        UUID id,
        UUID repositoryId,
        String architectureOverview,
        JsonNode importantModules,
        String setupInstructions,
        String dataFlow,
        JsonNode readFirst,
        Instant generatedAt
) {
}
