package com.codepilot.dto.ai;

import java.util.List;
import java.util.UUID;

public record AiQueryRequest(UUID repositoryId, String question, Integer topK, List<AiHistoryTurn> history) {
}
