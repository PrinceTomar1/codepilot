package com.codepilot.dto.qa;

import java.io.Serializable;

public record CitationDto(
        String filePath,
        Integer startLine,
        Integer endLine,
        String snippet
) implements Serializable {
}
