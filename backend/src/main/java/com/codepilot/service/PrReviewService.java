package com.codepilot.service;

import com.codepilot.dto.ai.AiReviewFile;
import com.codepilot.dto.ai.AiReviewRequest;
import com.codepilot.dto.ai.AiReviewResponse;
import com.codepilot.dto.github.GitHubPullRequestInfo;
import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.PullRequest;
import com.codepilot.entity.ReviewReport;
import com.codepilot.exception.ApiException;
import com.codepilot.repository.PullRequestRepository;
import com.codepilot.repository.ReviewReportRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * Runs the actual GitHub-fetch + AI-review work for a pull request off the webhook HTTP request
 * thread. WebhookService only verifies the webhook, upserts a PENDING_REVIEW row, and hands off
 * here -- GitHub's webhook delivery has a short response timeout, and a 4-agent AI review can
 * easily take longer than that, so this must not block the request that triggered it (the same
 * reasoning that already makes repository indexing @Async in IndexingService).
 */
@Service
@RequiredArgsConstructor
public class PrReviewService {

    private static final Logger log = LoggerFactory.getLogger(PrReviewService.class);

    private final PullRequestRepository pullRequestRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final GitHubClient gitHubClient;
    private final AiServiceClient aiServiceClient;
    private final EncryptionService encryptionService;
    private final RepositoryService repositoryService;
    private final RepositoryAccessTokenResolver accessTokenResolver;

    // Spring's @Async only takes effect when a method is called THROUGH the proxy -- a call from
    // another method on this same bean ("self-invocation") bypasses the proxy entirely and runs
    // synchronously on the caller's thread. WebhookService avoids this by calling reviewAsync() on
    // an INJECTED PrReviewService (a different bean, so it's a real proxied call); this class needs
    // the same real-async behavior when calling reviewAsync() from a method defined right here.
    // ObjectProvider (not a plain @Lazy self field) is what makes that actually work: Lombok's
    // @RequiredArgsConstructor doesn't copy field-level annotations onto the generated constructor
    // parameter, so a @Lazy PrReviewService field still resolves eagerly at construction time and
    // fails with "bean currently in creation" -- confirmed live, the app wouldn't even start.
    // ObjectProvider<T> is itself trivially constructible (it's a lookup handle, not the bean), so
    // it sidesteps the circularity entirely and only resolves the real proxy when .getObject() is
    // actually called, by which point the context is fully initialized.
    private final ObjectProvider<PrReviewService> selfProvider;

    /**
     * Manually triggers a review for a PR that exists on GitHub but hasn't arrived via webhook --
     * registering a webhook on GitHub's side isn't automated by this app (see docs/deployment.md),
     * so without this, a repository connected here has no way to ever get a review at all unless
     * an operator manually configures one. Mirrors WebhookService.processPullRequest's upsert +
     * after-commit-kickoff logic, but sources PR metadata from a direct GitHub API call instead of
     * a webhook payload.
     */
    @Transactional
    public UUID triggerManualReview(UUID userId, UUID repositoryId, int prNumber) {
        CodeRepository repo = repositoryService.findOwned(userId, repositoryId);
        String accessToken = encryptionService.decrypt(accessTokenResolver.resolve(repo));

        GitHubPullRequestInfo info = gitHubClient.fetchPullRequestInfo(
                repo.getGithubOwner(), repo.getGithubRepo(), prNumber, accessToken);
        if (info == null || info.number() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Pull request #" + prNumber + " not found on GitHub");
        }

        PullRequest pr = pullRequestRepository.findByRepositoryIdAndGithubPrNumber(repositoryId, prNumber)
                .orElseGet(() -> PullRequest.builder().repository(repo).githubPrNumber(prNumber).build());
        pr.setTitle(info.title());
        pr.setAuthor(info.user() != null ? info.user().login() : null);
        pr.setHeadSha(info.head() != null ? info.head().sha() : null);
        pr.setBaseSha(info.base() != null ? info.base().sha() : null);
        pr.setStatus("PENDING_REVIEW");
        pr = pullRequestRepository.save(pr);
        UUID pullRequestId = pr.getId();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    selfProvider.getObject().reviewAsync(pullRequestId);
                }
            });
        } else {
            selfProvider.getObject().reviewAsync(pullRequestId);
        }
        return pullRequestId;
    }

    @Async("indexingExecutor")
    public void reviewAsync(UUID pullRequestId) {
        // findByIdWithRepository, not findById: this runs on the async executor's thread, not the
        // one that created the PR row, so pr.getRepository() (a lazy proxy under plain findById)
        // can't be initialized here -- there's no open Hibernate session on this thread once the
        // query returns. The @EntityGraph fetch join loads it eagerly instead.
        PullRequest pr = pullRequestRepository.findByIdWithRepository(pullRequestId).orElse(null);
        if (pr == null) {
            log.error("reviewAsync: pull request {} not found", pullRequestId);
            return;
        }
        CodeRepository repo = pr.getRepository();

        try {
            String accessToken = encryptionService.decrypt(accessTokenResolver.resolve(repo));
            List<AiReviewFile> files = gitHubClient.fetchPullRequestFiles(
                    repo.getGithubOwner(), repo.getGithubRepo(), pr.getGithubPrNumber(), pr.getHeadSha(), accessToken);

            AiReviewResponse response = aiServiceClient.review(new AiReviewRequest(pr.getId(), files));

            ReviewReport report = ReviewReport.builder()
                    .pullRequest(pr)
                    .overallSummary(response.summary())
                    .bugs(response.findings() != null ? response.findings().bugs() : null)
                    .security(response.findings() != null ? response.findings().security() : null)
                    .codeSmells(response.findings() != null ? response.findings().codeSmells() : null)
                    .missingTests(response.findings() != null ? response.findings().missingTests() : null)
                    .performance(response.findings() != null ? response.findings().performance() : null)
                    .build();
            reviewReportRepository.save(report);

            pr.setStatus("REVIEWED");
            pullRequestRepository.save(pr);

            log.info("Review completed for PR #{} on {}/{}", pr.getGithubPrNumber(), repo.getGithubOwner(), repo.getGithubRepo());
        } catch (Exception e) {
            log.error("AI review failed for PR #{} on {}/{}: {}",
                    pr.getGithubPrNumber(), repo.getGithubOwner(), repo.getGithubRepo(), e.getMessage(), e);
            pr.setStatus("REVIEW_FAILED");
            pullRequestRepository.save(pr);
        }
    }
}
