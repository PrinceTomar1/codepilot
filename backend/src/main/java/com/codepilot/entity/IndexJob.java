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
@Table(name = "index_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndexJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    private CodeRepository repository;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(name = "files_indexed", nullable = false)
    @Builder.Default
    private Integer filesIndexed = 0;

    @Column(name = "chunks_created", nullable = false)
    @Builder.Default
    private Integer chunksCreated = 0;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(columnDefinition = "TEXT")
    private String error;

    @PrePersist
    void prePersist() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        if (status == null) {
            status = JobStatus.PENDING;
        }
    }

    public enum JobStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }
}
