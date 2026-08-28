package com.codepilot.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubOAuthTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("error") String error,
        @JsonProperty("error_description") String errorDescription
) {
}
