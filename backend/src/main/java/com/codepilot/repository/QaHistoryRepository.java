package com.codepilot.repository;

import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.QaHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QaHistoryRepository extends JpaRepository<QaHistory, UUID> {
    List<QaHistory> findByRepositoryOrderByCreatedAtDesc(CodeRepository repository);
    List<QaHistory> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);
}
