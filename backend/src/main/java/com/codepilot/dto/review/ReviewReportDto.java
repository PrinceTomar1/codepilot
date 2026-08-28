package com.codepilot.dto.review;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record ReviewReportDto(
        UUID id,
        UUID pullRequestId,
        Integer prNumber,
        String prTitle,
        String summary,
        Findings findings,
        Instant createdAt
) {
    public record Findings(
            JsonNode bugs,
            JsonNode security,
            JsonNode codeSmells,
            JsonNode missingTests,
            JsonNode performance
    ) {
    }
}
