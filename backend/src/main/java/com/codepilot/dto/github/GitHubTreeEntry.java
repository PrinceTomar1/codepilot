package com.codepilot.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTreeEntry(String path, String mode, String type, String sha, Long size) {
}
