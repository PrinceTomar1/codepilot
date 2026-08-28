package com.codepilot.repository;

import com.codepilot.entity.ReviewReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewReportRepository extends JpaRepository<ReviewReport, UUID> {

    @org.springframework.data.jpa.repository.Query(
            "select r from ReviewReport r where r.pullRequest.repository.id = :repositoryId order by r.createdAt desc")
    List<ReviewReport> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);
}
