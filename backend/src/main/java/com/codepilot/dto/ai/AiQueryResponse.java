package com.codepilot.dto.ai;

import java.io.Serializable;
import java.util.List;

public record AiQueryResponse(String answer, List<AiCitation> citations, Integer chunksRetrieved) implements Serializable {
}
