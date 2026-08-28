package com.codepilot.dto.search;

import java.util.List;

public record SearchResponseDto(List<SearchResultDto> results) {
}
