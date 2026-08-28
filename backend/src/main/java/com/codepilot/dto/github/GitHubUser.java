package com.codepilot.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubUser(
        @JsonProperty("id") Long id,
        @JsonProperty("login") String login,
        @JsonProperty("name") String name,
        @JsonProperty("email") String email
) {
}
