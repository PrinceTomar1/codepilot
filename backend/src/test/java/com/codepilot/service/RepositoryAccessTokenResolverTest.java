package com.codepilot.service;

import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.User;
import com.codepilot.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real bug: CodeRepository#accessTokenEncrypted is a one-time copy of the owner's GitHub OAuth
 * token, taken when the repo was connected -- it was never updated again, so re-authenticating
 * with GitHub silently broke every already-connected repo with a 401 on the next indexing/review
 * run. Confirmed live against two real repos before this fix. These tests lock in the resolution
 * order: the owner's CURRENT token wins whenever one exists, and only a repo with no owner token
 * on file (a manually pasted PAT, never a copy of anything) falls back to its own stored value.
 */
class RepositoryAccessTokenResolverTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RepositoryAccessTokenResolver resolver = new RepositoryAccessTokenResolver(userRepository);

    private CodeRepository repoOwnedBy(User owner) {
        return CodeRepository.builder()
                .id(UUID.randomUUID())
                .user(owner)
                .accessTokenEncrypted("stale-repo-copy")
                .build();
    }

    @Test
    void prefersTheOwnersCurrentGithubTokenOverTheRepositorysFrozenCopy() {
        User owner = User.builder().id(UUID.randomUUID())
                .githubAccessTokenEncrypted("fresh-user-token").build();
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));

        String resolved = resolver.resolve(repoOwnedBy(owner));

        assertThat(resolved).isEqualTo("fresh-user-token");
    }

    @Test
    void fallsBackToTheRepositorysOwnTokenWhenTheOwnerHasNoGithubOauthToken() {
        // e.g. a repo connected with a manually pasted personal access token, by a user who never
        // signed in with GitHub at all -- there is no "fresher" token to prefer here.
        User owner = User.builder().id(UUID.randomUUID())
                .githubAccessTokenEncrypted(null).build();
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));

        String resolved = resolver.resolve(repoOwnedBy(owner));

        assertThat(resolved).isEqualTo("stale-repo-copy");
    }

    @Test
    void treatsABlankOwnerTokenTheSameAsNoToken() {
        User owner = User.builder().id(UUID.randomUUID())
                .githubAccessTokenEncrypted("   ").build();
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));

        String resolved = resolver.resolve(repoOwnedBy(owner));

        assertThat(resolved).isEqualTo("stale-repo-copy");
    }

    @Test
    void fallsBackToTheRepositorysOwnTokenWhenTheOwnerRowIsGone() {
        User owner = User.builder().id(UUID.randomUUID()).build();
        when(userRepository.findById(owner.getId())).thenReturn(Optional.empty());

        String resolved = resolver.resolve(repoOwnedBy(owner));

        assertThat(resolved).isEqualTo("stale-repo-copy");
    }
}
