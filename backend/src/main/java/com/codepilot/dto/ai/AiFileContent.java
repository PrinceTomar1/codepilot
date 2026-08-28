package com.codepilot.dto.ai;

/** A single file's content, as sent to the AI service's /index endpoint. */
public record AiFileContent(String path, String language, String content) {
}
