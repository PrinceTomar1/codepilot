package com.codepilot.dto.ai;

import java.util.List;
import java.util.UUID;

public record AiIndexRequest(UUID repositoryId, List<AiFileContent> files) {
}
