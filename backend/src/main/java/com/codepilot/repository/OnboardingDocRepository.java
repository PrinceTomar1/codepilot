package com.codepilot.repository;

import com.codepilot.entity.OnboardingDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OnboardingDocRepository extends JpaRepository<OnboardingDoc, UUID> {
    Optional<OnboardingDoc> findFirstByRepositoryIdOrderByGeneratedAtDesc(UUID repositoryId);
}
