package com.codepilot.service;

import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.PullRequest;
import com.codepilot.repository.CodeRepositoryRepository;
import com.codepilot.repository.PullRequestRepository;
import com.codepilot.repository.ReviewReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Handles inbound GitHub webhooks: verifies the HMAC signature against the repository's stored
 * webhook_secret, deduplicates redeliveries of the same event, then reacts to pull_request
 * opened/synchronize events (kick off an AI review) and pushes to the default branch (kick off
 * re-indexing).
 */
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Set<String> REVIEWABLE_ACTIONS = Set.of("opened", "synchronize");
    private static final Duration DELIVERY_DEDUPE_TTL = Duration.ofHours(24);

    private final CodeRepositoryRepository codeRepositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final IndexingService indexingService;
    private final PrReviewService prReviewService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public enum Outcome { OK, UNAUTHORIZED }

    @Transactional
    public Outcome handle(String eventType, String deliveryId, String signatureHeader, String rawBody) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("Ignoring webhook with unparseable body: {}", e.getMessage());
            return Outcome.OK;
        }

        JsonNode repoNode = payload.path("repository");
        String owner = repoNode.path("owner").path("login").asText(null);
        String repoName = repoNode.path("name").asText(null);

        if (owner == null || repoName == null) {
            log.info("Ignoring webhook with no repository info (event={})", eventType);
            return Outcome.OK;
        }

        List<CodeRepository> candidates = codeRepositoryRepository
                .findByGithubOwnerIgnoreCaseAndGithubRepoIgnoreCase(owner, repoName);
        if (candidates.isEmpty()) {
            log.info("Ignoring webhook for untracked repository {}/{}", owner, repoName);
            return Outcome.OK;
        }

        CodeRepository repo = candidates.stream()
                .filter(c -> signatureMatches(signatureHeader, rawBody, c.getWebhookSecret()))
                .findFirst()
                .orElse(null);

        if (repo == null) {
            log.warn("Webhook signature verification failed for {}/{}", owner, repoName);
            return Outcome.UNAUTHORIZED;
        }

        // Only dedupe *after* the signature has verified, so an unauthenticated caller can't
        // pre-poison a delivery ID to make us silently drop a legitimate later delivery.
        if (deliveryId != null && !deliveryId.isBlank()
                && !idempotencyService.markFirstSeen("webhook:delivery:" + deliveryId, DELIVERY_DEDUPE_TTL)) {
            log.info("Ignoring duplicate webhook delivery {} for {}/{}", deliveryId, owner, repoName);
            return Outcome.OK;
        }

        if ("push".equals(eventType)) {
            try {
                processPush(repo, payload);
            } catch (Exception e) {
                log.error("Failed to process push webhook for {}/{}: {}", owner, repoName, e.getMessage(), e);
            }
            return Outcome.OK;
        }

        if (!"pull_request".equals(eventType)) {
            log.info("Ignoring unhandled webhook event type '{}'", eventType);
            return Outcome.OK;
        }

        String action = payload.path("action").asText(null);
        if (action == null || !REVIEWABLE_ACTIONS.contains(action)) {
            log.info("Ignoring pull_request action '{}'", action);
            return Outcome.OK;
        }

        try {
            processPullRequest(repo, payload);
        } catch (Exception e) {
            log.error("Failed to process pull_request webhook for {}/{}: {}", owner, repoName, e.getMessage(), e);
        }
        return Outcome.OK;
    }

    /**
     * A push landed on the repository. If it's on the default branch (or we don't know the
     * default branch yet), enqueue a re-index; the AI service's file-hash diffing means
     * unchanged files are skipped, so this is cheap for small pushes.
     */
    private void processPush(CodeRepository repo, JsonNode payload) {
        String ref = payload.path("ref").asText(null);
        String defaultBranch = repo.getDefaultBranch();
        boolean isDefaultBranchPush = defaultBranch == null
                || ("refs/heads/" + defaultBranch).equals(ref);

        if (!isDefaultBranchPush) {
            log.info("Ignoring push to non-default ref '{}' for {}/{}", ref, repo.getGithubOwner(), repo.getGithubRepo());
            return;
        }

        log.info("Push to {} on {}/{}: enqueuing re-index", ref, repo.getGithubOwner(), repo.getGithubRepo());
        indexingService.indexRepositoryAsync(repo.getId());
    }

    /**
     * Upserts the pull_requests row (fast, no external calls) and hands the actual review work
     * off to PrReviewService.reviewAsync, which runs on a background thread. This method itself
     * must stay fast: it's called synchronously from the webhook HTTP request, and GitHub expects
     * a prompt response -- the 4-agent AI review can easily take longer than GitHub's webhook
     * delivery timeout.
     */
    private void processPullRequest(CodeRepository repo, JsonNode payload) {
        JsonNode prNode = payload.path("pull_request");
        Integer prNumber = prNode.path("number").asInt();
        String title = prNode.path("title").asText(null);
        String author = prNode.path("user").path("login").asText(null);
        String headSha = prNode.path("head").path("sha").asText(null);
        String baseSha = prNode.path("base").path("sha").asText(null);

        PullRequest pr = pullRequestRepository.findByRepositoryIdAndGithubPrNumber(repo.getId(), prNumber)
                .orElseGet(() -> PullRequest.builder().repository(repo).githubPrNumber(prNumber).build());
        pr.setTitle(title);
        pr.setAuthor(author);
        pr.setHeadSha(headSha);
        pr.setBaseSha(baseSha);
        pr.setStatus("PENDING_REVIEW");
        pr = pullRequestRepository.save(pr);
        UUID pullRequestId = pr.getId();

        // Only kick off the async review once this transaction has actually committed -
        // otherwise the background thread could look up the PR before it's visible to other
        // connections (mirrors RepositoryService.createRepository's indexing kickoff).
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    prReviewService.reviewAsync(pullRequestId);
                }
            });
        } else {
            prReviewService.reviewAsync(pullRequestId);
        }
    }

    private boolean signatureMatches(String signatureHeader, String rawBody, String secret) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=") || secret == null) {
            return false;
        }
        String expectedHex = signatureHeader.substring("sha256=".length());
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] computed = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String computedHex = HexFormat.of().formatHex(computed);
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    expectedHex.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error computing HMAC for webhook signature check", e);
            return false;
        }
    }
}
