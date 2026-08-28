package com.codepilot.dto.repo;

import java.util.List;

public record ArchitectureGraphDto(List<ArchitectureNodeDto> nodes, List<ArchitectureEdgeDto> edges) {
}
