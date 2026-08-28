package com.codepilot.dto.review;

import java.util.UUID;

public record TriggerReviewResponse(UUID pullRequestId, String status) {
}
