package com.codepilot.repository;

import com.codepilot.entity.IndexJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IndexJobRepository extends JpaRepository<IndexJob, UUID> {
    Optional<IndexJob> findFirstByRepositoryIdOrderByStartedAtDesc(UUID repositoryId);
}
