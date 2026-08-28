package com.codepilot.dto.ai;

import java.util.UUID;

public record AiIndexResponse(UUID repositoryId, Integer filesIndexed, Integer chunksCreated, String status) {
}
