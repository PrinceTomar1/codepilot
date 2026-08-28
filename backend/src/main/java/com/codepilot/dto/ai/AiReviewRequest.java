package com.codepilot.dto.ai;

import java.util.List;
import java.util.UUID;

public record AiReviewRequest(UUID pullRequestId, List<AiReviewFile> files) {
}
