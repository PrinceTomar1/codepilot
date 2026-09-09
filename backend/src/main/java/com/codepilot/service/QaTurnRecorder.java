package com.codepilot.service;

import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.QaHistory;
import com.codepilot.entity.User;
import com.codepilot.repository.CodeRepositoryRepository;
import com.codepilot.repository.QaHistoryRepository;
import com.codepilot.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists one completed Q&A turn in its own transaction. Split out of {@link QaService} because
 * the streaming path (askStream) finishes on a background thread, after the original request's
 * transaction and Hibernate session are long gone -- so the {@code repository}/{@code user}
 * entities from that request are detached and must be re-loaded here by id.
 */
@Component
@RequiredArgsConstructor
public class QaTurnRecorder {

    private static final Logger log = LoggerFactory.getLogger(QaTurnRecorder.class);

    private final CodeRepositoryRepository codeRepositoryRepository;
    private final UserRepository userRepository;
    private final QaHistoryRepository qaHistoryRepository;

    @Transactional
    public void record(UUID repositoryId, UUID userId, String question, String answer, JsonNode citations) {
        CodeRepository repo = codeRepositoryRepository.findById(repositoryId).orElse(null);
        User user = userRepository.findById(userId).orElse(null);
        if (repo == null || user == null) {
            // The repo/user existed when the question was accepted; if one is gone now the answer
            // was still delivered to the client -- just skip the history row rather than fail.
            log.warn("Skipping Q&A history row: repository {} or user {} no longer exists", repositoryId, userId);
            return;
        }
        qaHistoryRepository.save(QaHistory.builder()
                .repository(repo)
                .user(user)
                .question(question)
                .answer(answer)
                .citations(citations)
                .build());
    }
}
