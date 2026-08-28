package com.codepilot.dto.ai;

import com.fasterxml.jackson.databind.JsonNode;

public record AiOnboardingResponse(
        String architectureOverview,
        JsonNode importantModules,
        String setupInstructions,
        String dataFlow,
        JsonNode readFirst
) {
}
