package com.codepilot.service;

import com.codepilot.dto.review.ReviewReportDto;
import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.PullRequest;
import com.codepilot.entity.ReviewReport;
import com.codepilot.repository.ReviewReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real bug: ReviewReportDto used to serialize as {githubPrNumber, overallSummary,
 * bugs, security, codeSmells, missingTests, performance} -- flat fields with different names
 * than what the frontend has always expected ({prNumber, summary, findings: {bugs, security,
 * ...}}). ReviewList showed a blank PR number/summary for every review, and ReviewDetail hard
 * crashed with a TypeError reading `review.findings[key]` on undefined. These tests lock in the
 * JSON shape the frontend actually consumes (frontend/src/types/review.ts), not just that some
 * DTO gets returned.
 */
class ReviewServiceTest {

    private final RepositoryService repositoryService = mock(RepositoryService.class);
    private final ReviewReportRepository reviewReportRepository = mock(ReviewReportRepository.class);
    private final ReviewService reviewService = new ReviewService(repositoryService, reviewReportRepository);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode findings(String description) {
        return objectMapper.createArrayNode().add(
                objectMapper.createObjectNode().put("description", description));
    }

    private ReviewReport sampleReport() {
        CodeRepository repo = CodeRepository.builder().id(UUID.randomUUID()).build();
        PullRequest pr = PullRequest.builder()
                .id(UUID.randomUUID())
                .repository(repo)
                .githubPrNumber(42)
                .title("Fix the bug")
                .build();
        return ReviewReport.builder()
                .id(UUID.randomUUID())
                .pullRequest(pr)
                .overallSummary("Looks mostly fine")
                .bugs(findings("off-by-one"))
                .security(findings("hardcoded key"))
                .codeSmells(findings("long method"))
                .missingTests(findings("no coverage for edge case"))
                .performance(findings("N+1 query"))
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void dtoUsesTheFieldNamesTheFrontendActuallyReadsNotTheEntitysInternalNames() {
        UUID userId = UUID.randomUUID();
        ReviewReport report = sampleReport();
        when(reviewReportRepository.findById(report.getId())).thenReturn(Optional.of(report));

        ReviewReportDto dto = reviewService.getById(userId, report.getId());

        // The frontend's ReviewSummary/ReviewDetail types read `prNumber` and `summary`, never
        // `githubPrNumber`/`overallSummary` -- those were the old (wrong) field names.
        assertThat(dto.prNumber()).isEqualTo(42);
        assertThat(dto.prTitle()).isEqualTo("Fix the bug");
        assertThat(dto.summary()).isEqualTo("Looks mostly fine");
    }

    @Test
    void dtoNestsTheFiveFindingCategoriesUnderASingleFindingsObject() {
        UUID userId = UUID.randomUUID();
        ReviewReport report = sampleReport();
        when(reviewReportRepository.findById(report.getId())).thenReturn(Optional.of(report));

        ReviewReportDto dto = reviewService.getById(userId, report.getId());

        // ReviewDetail.tsx reads review.findings.bugs / .security / etc, not top-level fields --
        // a flat DTO here means `review.findings` is undefined and the frontend crashes.
        assertThat(dto.findings()).isNotNull();
        assertThat(dto.findings().bugs().get(0).get("description").asText()).isEqualTo("off-by-one");
        assertThat(dto.findings().security().get(0).get("description").asText()).isEqualTo("hardcoded key");
        assertThat(dto.findings().codeSmells().get(0).get("description").asText()).isEqualTo("long method");
        assertThat(dto.findings().missingTests().get(0).get("description").asText()).isEqualTo("no coverage for edge case");
        assertThat(dto.findings().performance().get(0).get("description").asText()).isEqualTo("N+1 query");
    }

    @Test
    void listForRepositoryChecksOwnershipBeforeReturningReviews() {
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        when(repositoryService.findOwned(userId, repositoryId))
                .thenReturn(CodeRepository.builder().id(repositoryId).build());
        when(reviewReportRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId))
                .thenReturn(List.of(sampleReport()));

        List<ReviewReportDto> reviews = reviewService.listForRepository(userId, repositoryId);

        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).prNumber()).isEqualTo(42);
    }
}
