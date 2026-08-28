package com.codepilot.service;

import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.User;
import com.codepilot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * CodeRepository#accessTokenEncrypted is a one-time copy taken when the repo was connected (see
 * RepositoryService#createRepository/#createFromGitHub) -- for repos connected via GitHub OAuth,
 * that copy goes stale forever the next time the owning user re-authenticates with GitHub, since
 * nothing ever updates it, and every GitHub call for that repo then fails with a 401. Every call
 * site that talks to GitHub on a repository's behalf should resolve the token through here rather
 * than reading CodeRepository#accessTokenEncrypted directly, so a fresh login on the owner's
 * account fixes every one of their repos automatically instead of only the next one connected.
 * Falls back to the repo's own stored token when the owner has no GitHub OAuth token on file --
 * true for repos connected with a manually pasted personal access token, which was never a copy
 * of the user's own token and has no "fresher" version to fall back to.
 */
@Service
@RequiredArgsConstructor
public class RepositoryAccessTokenResolver {

    private final UserRepository userRepository;

    public String resolve(CodeRepository repo) {
        User owner = userRepository.findById(repo.getUser().getId()).orElse(null);
        String ownerToken = owner != null ? owner.getGithubAccessTokenEncrypted() : null;
        return (ownerToken != null && !ownerToken.isBlank()) ? ownerToken : repo.getAccessTokenEncrypted();
    }
}
