package com.codepilot.dto.repo;

/** One entry in the "pick a repo from your GitHub account" list shown instead of a PAT paste box. */
public record GitHubRepoOptionDto(
        String owner,
        String name,
        boolean isPrivate,
        String defaultBranch
) {
}
