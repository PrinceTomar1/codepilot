package com.codepilot.dto.qa;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record QaHistoryDto(
        UUID id,
        String question,
        String answer,
        JsonNode citations,
        Instant createdAt
) {
}
