package com.codepilot.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullRequestFile(String filename, String status, String patch, String sha) {
}
