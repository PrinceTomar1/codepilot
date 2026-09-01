package com.codepilot.service;

import com.codepilot.dto.ai.AiReviewResponse;
import com.codepilot.dto.github.GitHubPullRequestInfo;
import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.PullRequest;
import com.codepilot.entity.ReviewReport;
import com.codepilot.exception.ApiException;
import com.codepilot.repository.PullRequestRepository;
import com.codepilot.repository.ReviewReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrReviewServiceTest {

    private PullRequestRepository pullRequestRepository;
    private ReviewReportRepository reviewReportRepository;
    private GitHubClient gitHubClient;
    private AiServiceClient aiServiceClient;
    private EncryptionService encryptionService;
    private RepositoryService repositoryService;
    private RepositoryAccessTokenResolver accessTokenResolver;
    private PrReviewService selfProxy;
    private PrReviewService prReviewService;

    @BeforeEach
    void setUp() {
        pullRequestRepository = mock(PullRequestRepository.class);
        reviewReportRepository = mock(ReviewReportRepository.class);
        gitHubClient = mock(GitHubClient.class);
        aiServiceClient = mock(AiServiceClient.class);
        encryptionService = mock(EncryptionService.class);
        repositoryService = mock(RepositoryService.class);
        accessTokenResolver = mock(RepositoryAccessTokenResolver.class);
        // Token resolution itself (fresh owner token vs. stale repo copy) is covered in
        // RepositoryAccessTokenResolverTest -- here it's just a pass-through to whatever the repo
        // had stored, so the rest of this file's assertions don't need to change.
        when(accessTokenResolver.resolve(any())).thenReturn("encrypted-token");

        // The real ObjectProvider-resolved self proxy only exists in a running Spring context --
        // here it's a mock whose reviewAsync() delegates straight back to the real instance, since
        // a plain unit test has no async executor anyway (every existing test in this file already
        // treats reviewAsync() as synchronous). What actually matters for triggerManualReview()'s
        // own tests is that it calls THROUGH the provider, not directly -- see PrReviewService's
        // field comment, and manualTriggerCreatesNewPullRequestRowAndStartsReview's verify() below.
        selfProxy = mock(PrReviewService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PrReviewService> selfProvider = mock(ObjectProvider.class);
        when(selfProvider.getObject()).thenReturn(selfProxy);
        prReviewService = new PrReviewService(pullRequestRepository, reviewReportRepository, gitHubClient,
                aiServiceClient, encryptionService, repositoryService, accessTokenResolver, selfProvider);
        doAnswer(inv -> {
            prReviewService.reviewAsync(inv.getArgument(0));
            return null;
        }).when(selfProxy).reviewAsync(any());
    }

    private PullRequest samplePr() {
        CodeRepository repo = CodeRepository.builder()
                .id(UUID.randomUUID())
                .githubOwner("octocat")
                .githubRepo("hello-world")
                .accessTokenEncrypted("encrypted-token")
                .build();
        return PullRequest.builder()
                .id(UUID.randomUUID())
                .repository(repo)
                .githubPrNumber(42)
                .headSha("deadbeef")
                .status("PENDING_REVIEW")
                .build();
    }

    @Test
    void successfulReviewPersistsReportAndMarksReviewed() {
        PullRequest pr = samplePr();
        when(pullRequestRepository.findByIdWithRepository(pr.getId())).thenReturn(Optional.of(pr));
        when(pullRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.decrypt(anyString())).thenReturn("plain-token");
        when(gitHubClient.fetchPullRequestFiles(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(List.of());
        when(aiServiceClient.review(any())).thenReturn(new AiReviewResponse("looks fine", null));

        prReviewService.reviewAsync(pr.getId());

        verify(reviewReportRepository).save(any(ReviewReport.class));
        assertThat(pr.getStatus()).isEqualTo("REVIEWED");
    }

    @Test
    void githubFailureMarksReviewFailedWithoutPersistingAReport() {
        PullRequest pr = samplePr();
        when(pullRequestRepository.findByIdWithRepository(pr.getId())).thenReturn(Optional.of(pr));
        when(pullRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.decrypt(anyString())).thenReturn("plain-token");
        when(gitHubClient.fetchPullRequestFiles(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("GitHub API unreachable"));

        prReviewService.reviewAsync(pr.getId());

        verify(reviewReportRepository, never()).save(any());
        assertThat(pr.getStatus()).isEqualTo("REVIEW_FAILED");
    }

    @Test
    void unknownPullRequestIdIsANoOp() {
        UUID missingId = UUID.randomUUID();
        when(pullRequestRepository.findByIdWithRepository(missingId)).thenReturn(Optional.empty());

        prReviewService.reviewAsync(missingId);

        verify(reviewReportRepository, never()).save(any());
        verify(pullRequestRepository, never()).save(any());
    }

    private CodeRepository sampleRepo() {
        return CodeRepository.builder()
                .id(UUID.randomUUID())
                .githubOwner("octocat")
                .githubRepo("hello-world")
                .accessTokenEncrypted("encrypted-token")
                .build();
    }

    @Test
    void manualTriggerCreatesNewPullRequestRowAndStartsReview() {
        UUID userId = UUID.randomUUID();
        CodeRepository repo = sampleRepo();
        java.util.concurrent.atomic.AtomicReference<PullRequest> savedRef = new java.util.concurrent.atomic.AtomicReference<>();

        when(repositoryService.findOwned(userId, repo.getId())).thenReturn(repo);
        when(encryptionService.decrypt(anyString())).thenReturn("plain-token");
        when(gitHubClient.fetchPullRequestInfo("octocat", "hello-world", 7, "plain-token"))
                .thenReturn(new GitHubPullRequestInfo(7, "Fix bug", "open",
                        new GitHubPullRequestInfo.User("octocat"),
                        new GitHubPullRequestInfo.Ref("headsha"), new GitHubPullRequestInfo.Ref("basesha")));
        when(pullRequestRepository.findByRepositoryIdAndGithubPrNumber(repo.getId(), 7)).thenReturn(Optional.empty());
        when(pullRequestRepository.save(any())).thenAnswer(inv -> {
            PullRequest pr = inv.getArgument(0);
            if (pr.getId() == null) {
                pr.setId(UUID.randomUUID());
            }
            savedRef.set(pr);
            return pr;
        });
        // reviewAsync (kicked off after the upsert) looks the row back up by id -- since this
        // test has no real transaction/synchronization active, that call happens synchronously
        // within triggerManualReview() itself, so the row must already be resolvable here.
        when(pullRequestRepository.findByIdWithRepository(any()))
                .thenAnswer(inv -> Optional.ofNullable(savedRef.get()));
        when(gitHubClient.fetchPullRequestFiles(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(List.of());
        when(aiServiceClient.review(any())).thenReturn(new AiReviewResponse("looks fine", null));

        UUID pullRequestId = prReviewService.triggerManualReview(userId, repo.getId(), 7);

        PullRequest saved = savedRef.get();
        assertThat(saved.getGithubPrNumber()).isEqualTo(7);
        assertThat(saved.getTitle()).isEqualTo("Fix bug");
        assertThat(saved.getAuthor()).isEqualTo("octocat");
        assertThat(saved.getHeadSha()).isEqualTo("headsha");
        assertThat(saved.getBaseSha()).isEqualTo("basesha");
        assertThat(pullRequestId).isEqualTo(saved.getId());
        // reviewAsync ran to completion synchronously and marked the PR reviewed.
        assertThat(saved.getStatus()).isEqualTo("REVIEWED");
        // The real bug this guards: reviewAsync() must be called through the injected self proxy,
        // not as a bare/this-qualified call -- a bare call bypasses Spring's @Async proxy entirely
        // (self-invocation), so the review would run SYNCHRONOUSLY inside the HTTP request thread
        // instead of in the background. Before this fix, a real trigger request blocked for the
        // full duration of the AI review instead of returning immediately.
        verify(selfProxy).reviewAsync(pullRequestId);
    }

    @Test
    void manualTriggerReusesExistingPullRequestRowInsteadOfDuplicating() {
        UUID userId = UUID.randomUUID();
        CodeRepository repo = sampleRepo();
        PullRequest existing = PullRequest.builder().id(UUID.randomUUID()).repository(repo)
                .githubPrNumber(7).status("REVIEWED").build();

        when(repositoryService.findOwned(userId, repo.getId())).thenReturn(repo);
        when(encryptionService.decrypt(anyString())).thenReturn("plain-token");
        when(gitHubClient.fetchPullRequestInfo(eq("octocat"), eq("hello-world"), eq(7), anyString()))
                .thenReturn(new GitHubPullRequestInfo(7, "Updated title", "open",
                        new GitHubPullRequestInfo.User("octocat"),
                        new GitHubPullRequestInfo.Ref("newhead"), new GitHubPullRequestInfo.Ref("newbase")));
        when(pullRequestRepository.findByRepositoryIdAndGithubPrNumber(repo.getId(), 7))
                .thenReturn(Optional.of(existing));
        when(pullRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pullRequestRepository.findByIdWithRepository(existing.getId())).thenReturn(Optional.of(existing));
        when(gitHubClient.fetchPullRequestFiles(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(List.of());
        when(aiServiceClient.review(any())).thenReturn(new AiReviewResponse("looks fine", null));

        UUID pullRequestId = prReviewService.triggerManualReview(userId, repo.getId(), 7);

        assertThat(pullRequestId).isEqualTo(existing.getId());
        assertThat(existing.getTitle()).isEqualTo("Updated title");
        assertThat(existing.getHeadSha()).isEqualTo("newhead");
    }

    @Test
    void manualTriggerThrowsNotFoundWhenPrDoesNotExistOnGithub() {
        UUID userId = UUID.randomUUID();
        CodeRepository repo = sampleRepo();
        when(repositoryService.findOwned(userId, repo.getId())).thenReturn(repo);
        when(encryptionService.decrypt(anyString())).thenReturn("plain-token");
        when(gitHubClient.fetchPullRequestInfo(anyString(), anyString(), anyInt(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> prReviewService.triggerManualReview(userId, repo.getId(), 999))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");

        verify(pullRequestRepository, never()).save(any());
    }

    @Test
    void manualTriggerPropagatesOwnershipFailureWithoutCallingGithub() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        when(repositoryService.findOwned(userId, repositoryId))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "not yours"));

        assertThatThrownBy(() -> prReviewService.triggerManualReview(userId, repositoryId, 1))
                .isInstanceOf(ApiException.class);

        verify(gitHubClient, never()).fetchPullRequestInfo(anyString(), anyString(), anyInt(), anyString());
    }
}
