package com.codepilot.dto.ai;

/** A single changed file, as sent to the AI service's /review endpoint. */
public record AiReviewFile(String path, String diff, String fullContent) {
}
