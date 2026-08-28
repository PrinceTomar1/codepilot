package com.codepilot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "code_repositories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeRepository {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "github_owner", nullable = false)
    private String githubOwner;

    @Column(name = "github_repo", nullable = false)
    private String githubRepo;

    @Column(name = "github_repo_id")
    private Long githubRepoId;

    @Column(name = "default_branch")
    private String defaultBranch;

    @Column(name = "webhook_secret")
    private String webhookSecret;

    @Column(name = "access_token_encrypted", columnDefinition = "TEXT")
    private String accessTokenEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RepositoryStatus status;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = RepositoryStatus.PENDING;
        }
    }

    public enum RepositoryStatus {
        PENDING, INDEXING, INDEXED, FAILED
    }
}
