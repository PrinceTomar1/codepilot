package com.codepilot.dto.ai;

public record AiSearchResult(
        String filePath,
        String language,
        Integer startLine,
        Integer endLine,
        String snippet,
        String symbolName,
        String matchType,
        Double relevanceScore
) {
}
