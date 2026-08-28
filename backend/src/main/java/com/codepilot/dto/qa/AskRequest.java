package com.codepilot.dto.qa;

import jakarta.validation.constraints.NotBlank;

public record AskRequest(@NotBlank String question) {
}
