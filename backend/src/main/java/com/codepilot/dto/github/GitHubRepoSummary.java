package com.codepilot.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One entry from GET /user/repos -- used to render a repo picker instead of asking for a PAT. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepoSummary(
        @JsonProperty("id") Long id,
        @JsonProperty("name") String name,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("private") boolean isPrivate,
        @JsonProperty("default_branch") String defaultBranch,
        @JsonProperty("owner") Owner owner
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(@JsonProperty("login") String login) {
    }
}
