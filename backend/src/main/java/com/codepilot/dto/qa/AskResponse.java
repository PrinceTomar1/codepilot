package com.codepilot.dto.qa;

import java.io.Serializable;
import java.util.List;

public record AskResponse(
        String answer,
        List<CitationDto> citations,
        Integer chunksRetrieved
) implements Serializable {
}
