package com.codepilot.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubEmail(
        @JsonProperty("email") String email,
        @JsonProperty("primary") boolean primary,
        @JsonProperty("verified") boolean verified
) {
}
