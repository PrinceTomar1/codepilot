package com.codepilot.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepoInfo(
        @JsonProperty("id") Long id,
        @JsonProperty("default_branch") String defaultBranch,
        @JsonProperty("full_name") String fullName
) {
}
