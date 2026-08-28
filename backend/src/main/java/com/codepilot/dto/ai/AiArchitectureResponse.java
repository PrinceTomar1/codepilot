package com.codepilot.dto.ai;

import java.util.List;

public record AiArchitectureResponse(List<AiArchitectureNode> nodes, List<AiArchitectureEdge> edges) {
}
