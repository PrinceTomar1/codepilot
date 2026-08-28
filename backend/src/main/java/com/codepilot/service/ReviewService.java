package com.codepilot.service;

import com.codepilot.dto.review.ReviewReportDto;
import com.codepilot.entity.ReviewReport;
import com.codepilot.exception.ApiException;
import com.codepilot.repository.ReviewReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final RepositoryService repositoryService;
    private final ReviewReportRepository reviewReportRepository;

    @Transactional(readOnly = true)
    public List<ReviewReportDto> listForRepository(UUID userId, UUID repositoryId) {
        repositoryService.findOwned(userId, repositoryId);
        return reviewReportRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReviewReportDto getById(UUID userId, UUID reviewId) {
        ReviewReport report = reviewReportRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Review report not found"));

        UUID repositoryId = report.getPullRequest().getRepository().getId();
        repositoryService.findOwned(userId, repositoryId);

        return toDto(report);
    }

    private ReviewReportDto toDto(ReviewReport r) {
        return new ReviewReportDto(
                r.getId(),
                r.getPullRequest().getId(),
                r.getPullRequest().getGithubPrNumber(),
                r.getPullRequest().getTitle(),
                r.getOverallSummary(),
                new ReviewReportDto.Findings(
                        r.getBugs(),
                        r.getSecurity(),
                        r.getCodeSmells(),
                        r.getMissingTests(),
                        r.getPerformance()
                ),
                r.getCreatedAt()
        );
    }
}
