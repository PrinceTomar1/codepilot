package com.codepilot.repository;

import com.codepilot.entity.PullRequest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PullRequestRepository extends JpaRepository<PullRequest, UUID> {
    Optional<PullRequest> findByRepositoryIdAndGithubPrNumber(UUID repositoryId, Integer githubPrNumber);

    /**
     * Eagerly fetches the associated CodeRepository in the same query. Needed for any caller that
     * reads pr.getRepository()'s fields outside the request thread that originally loaded the PR
     * (e.g. an @Async job on a different thread) -- the default lazy proxy can't be initialized
     * once that thread's transaction/session is gone.
     */
    @EntityGraph(attributePaths = "repository")
    @Query("SELECT pr FROM PullRequest pr WHERE pr.id = :id")
    Optional<PullRequest> findByIdWithRepository(@Param("id") UUID id);
}
