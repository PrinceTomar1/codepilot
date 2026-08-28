package com.codepilot.dto.ai;

import com.fasterxml.jackson.databind.JsonNode;

/** Opaque findings arrays - stored as-is into the review_reports JSONB columns. */
public record AiReviewFindings(
        JsonNode bugs,
        JsonNode security,
        JsonNode codeSmells,
        JsonNode missingTests,
        JsonNode performance
) {
}
