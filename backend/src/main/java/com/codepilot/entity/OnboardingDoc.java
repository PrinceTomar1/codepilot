package com.codepilot.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "onboarding_docs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardingDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    private CodeRepository repository;

    @Column(name = "architecture_overview", columnDefinition = "TEXT")
    private String architectureOverview;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "important_modules", columnDefinition = "jsonb")
    private JsonNode importantModules;

    @Column(name = "setup_instructions", columnDefinition = "TEXT")
    private String setupInstructions;

    @Column(name = "data_flow", columnDefinition = "TEXT")
    private String dataFlow;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "read_first", columnDefinition = "jsonb")
    private JsonNode readFirst;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @PrePersist
    void prePersist() {
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
    }
}
