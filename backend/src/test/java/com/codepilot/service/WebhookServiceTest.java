package com.codepilot.service;

import com.codepilot.entity.CodeRepository;
import com.codepilot.repository.CodeRepositoryRepository;
import com.codepilot.repository.PullRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebhookService's own job is verify -> dedupe -> upsert -> hand off. The actual GitHub-fetch +
 * AI-review work now lives in PrReviewService (see PrReviewServiceTest) and runs off the request
 * thread, so these tests mock PrReviewService and assert on hand-off counts, not on what the
 * review itself produces.
 */
class WebhookServiceTest {

    private static final String SECRET = "test-webhook-secret";

    private CodeRepositoryRepository codeRepositoryRepository;
    private PullRequestRepository pullRequestRepository;
    private IndexingService indexingService;
    private PrReviewService prReviewService;
    private IdempotencyService idempotencyService;
    private WebhookService webhookService;

    @BeforeEach
    void setUp() {
        codeRepositoryRepository = mock(CodeRepositoryRepository.class);
        pullRequestRepository = mock(PullRequestRepository.class);
        indexingService = mock(IndexingService.class);
        prReviewService = mock(PrReviewService.class);
        idempotencyService = mock(IdempotencyService.class);

        webhookService = new WebhookService(
                codeRepositoryRepository,
                pullRequestRepository,
                indexingService,
                prReviewService,
                idempotencyService,
                new ObjectMapper());
    }

    private CodeRepository sampleRepo() {
        return CodeRepository.builder()
                .id(UUID.randomUUID())
                .githubOwner("octocat")
                .githubRepo("hello-world")
                .defaultBranch("main")
                .webhookSecret(SECRET)
                .accessTokenEncrypted("encrypted-token")
                .build();
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String pullRequestPayload(String action) {
        return """
                {
                  "action": "%s",
                  "repository": {"name": "hello-world", "owner": {"login": "octocat"}},
                  "pull_request": {"number": 42, "title": "Fix bug", "user": {"login": "alice"},
                                    "head": {"sha": "deadbeef"}, "base": {"sha": "cafebabe"}}
                }
                """.formatted(action);
    }

    private String pushPayload(String ref) {
        return """
                {
                  "ref": "%s",
                  "repository": {"name": "hello-world", "owner": {"login": "octocat"}}
                }
                """.formatted(ref);
    }

    @Test
    void rejectsPayloadWithBadSignature() {
        CodeRepository repo = sampleRepo();
        when(codeRepositoryRepository.findByGithubOwnerIgnoreCaseAndGithubRepoIgnoreCase("octocat", "hello-world"))
                .thenReturn(List.of(repo));

        String body = pullRequestPayload("opened");
        WebhookService.Outcome outcome = webhookService.handle("pull_request", "delivery-1", "sha256=wrong", body);

        assertThat(outcome).isEqualTo(WebhookService.Outcome.UNAUTHORIZED);
        verify(prReviewService, never()).reviewAsync(any());
    }

    @Test
    void duplicateDeliveryIsProcessedOnlyOnce() throws Exception {
        CodeRepository repo = sampleRepo();
        when(codeRepositoryRepository.findByGithubOwnerIgnoreCaseAndGithubRepoIgnoreCase("octocat", "hello-world"))
                .thenReturn(List.of(repo));
        when(pullRequestRepository.findByRepositoryIdAndGithubPrNumber(any(), any()))
                .thenReturn(Optional.empty());
        when(pullRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // First delivery: not seen before -> proceeds.
        when(idempotencyService.markFirstSeen("webhook:delivery:delivery-1", Duration.ofHours(24)))
                .thenReturn(true, false); // true on 1st call, false on every call after

        String body = pullRequestPayload("opened");
        String signature = sign(body);

        WebhookService.Outcome first = webhookService.handle("pull_request", "delivery-1", signature, body);
        WebhookService.Outcome second = webhookService.handle("pull_request", "delivery-1", signature, body);

        assertThat(first).isEqualTo(WebhookService.Outcome.OK);
        assertThat(second).isEqualTo(WebhookService.Outcome.OK);
        // No real Spring transaction is active in this unit test, so processPullRequest takes the
        // synchronous hand-off branch -- exactly once, since the second delivery is deduped
        // before it ever reaches that code.
        verify(prReviewService, times(1)).reviewAsync(any());
    }

    @Test
    void pushToDefaultBranchTriggersReindex() throws Exception {
        CodeRepository repo = sampleRepo();
        when(codeRepositoryRepository.findByGithubOwnerIgnoreCaseAndGithubRepoIgnoreCase("octocat", "hello-world"))
                .thenReturn(List.of(repo));
        when(idempotencyService.markFirstSeen(anyString(), any())).thenReturn(true);

        String body = pushPayload("refs/heads/main");
        WebhookService.Outcome outcome = webhookService.handle("push", "delivery-push-1", sign(body), body);

        assertThat(outcome).isEqualTo(WebhookService.Outcome.OK);
        verify(indexingService, times(1)).indexRepositoryAsync(repo.getId());
    }

    @Test
    void pushToNonDefaultBranchIsIgnored() throws Exception {
        CodeRepository repo = sampleRepo();
        when(codeRepositoryRepository.findByGithubOwnerIgnoreCaseAndGithubRepoIgnoreCase("octocat", "hello-world"))
                .thenReturn(List.of(repo));
        when(idempotencyService.markFirstSeen(anyString(), any())).thenReturn(true);

        String body = pushPayload("refs/heads/feature-branch");
        WebhookService.Outcome outcome = webhookService.handle("push", "delivery-push-2", sign(body), body);

        assertThat(outcome).isEqualTo(WebhookService.Outcome.OK);
        verify(indexingService, never()).indexRepositoryAsync(any());
    }
}
