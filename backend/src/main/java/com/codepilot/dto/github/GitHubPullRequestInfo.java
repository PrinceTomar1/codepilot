package com.codepilot.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** GET /repos/{owner}/{repo}/pulls/{number} -- used to manually trigger a review for a PR that
 * exists on GitHub but hasn't (yet) arrived via webhook. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullRequestInfo(
        @JsonProperty("number") Integer number,
        @JsonProperty("title") String title,
        @JsonProperty("state") String state,
        @JsonProperty("user") User user,
        @JsonProperty("head") Ref head,
        @JsonProperty("base") Ref base
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(@JsonProperty("login") String login) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(@JsonProperty("sha") String sha) {
    }
}
