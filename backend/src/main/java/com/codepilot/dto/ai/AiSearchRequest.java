package com.codepilot.dto.ai;

import java.util.UUID;

public record AiSearchRequest(UUID repositoryId, String query, Integer topK) {
}
