package com.codepilot.dto.search;

import jakarta.validation.constraints.NotBlank;

public record SearchQueryRequest(@NotBlank String query) {
}
