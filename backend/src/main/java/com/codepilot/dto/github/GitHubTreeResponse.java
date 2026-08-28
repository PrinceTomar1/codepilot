package com.codepilot.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTreeResponse(String sha, List<GitHubTreeEntry> tree, boolean truncated) {
}
