package com.codepilot.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubBlob(String sha, String content, String encoding, Long size) {
}
