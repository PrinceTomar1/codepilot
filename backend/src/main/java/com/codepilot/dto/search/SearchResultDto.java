package com.codepilot.dto.search;

public record SearchResultDto(
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
