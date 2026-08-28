package com.codepilot.controller;

import com.codepilot.dto.review.ReviewReportDto;
import com.codepilot.dto.review.TriggerReviewResponse;
import com.codepilot.security.UserPrincipal;
import com.codepilot.service.PrReviewService;
import com.codepilot.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final PrReviewService prReviewService;

    @GetMapping("/api/repositories/{id}/reviews")
    public ResponseEntity<List<ReviewReportDto>> listForRepository(@AuthenticationPrincipal UserPrincipal principal,
                                                                     @PathVariable("id") UUID repositoryId) {
        return ResponseEntity.ok(reviewService.listForRepository(principal.getId(), repositoryId));
    }

    @GetMapping("/api/reviews/{id}")
    public ResponseEntity<ReviewReportDto> getById(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable("id") UUID reviewId) {
        return ResponseEntity.ok(reviewService.getById(principal.getId(), reviewId));
    }

    /**
     * Manually starts a review for a PR that exists on GitHub but hasn't arrived via webhook --
     * webhook registration on GitHub's side isn't automated by this app, so without this a
     * connected repository has no way to ever get a review unless one is configured manually.
     * The review itself still runs asynchronously; this returns as soon as it's queued.
     */
    @PostMapping("/api/repositories/{id}/pull-requests/{prNumber}/review")
    public ResponseEntity<TriggerReviewResponse> triggerReview(@AuthenticationPrincipal UserPrincipal principal,
                                                                 @PathVariable("id") UUID repositoryId,
                                                                 @PathVariable("prNumber") int prNumber) {
        UUID pullRequestId = prReviewService.triggerManualReview(principal.getId(), repositoryId, prNumber);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new TriggerReviewResponse(pullRequestId, "PENDING_REVIEW"));
    }
}
