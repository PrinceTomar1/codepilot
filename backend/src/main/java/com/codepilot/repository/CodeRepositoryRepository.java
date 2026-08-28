package com.codepilot.repository;

import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CodeRepositoryRepository extends JpaRepository<CodeRepository, UUID> {
    List<CodeRepository> findByUserOrderByCreatedAtDesc(User user);

    List<CodeRepository> findByGithubOwnerIgnoreCaseAndGithubRepoIgnoreCase(String githubOwner, String githubRepo);
}
