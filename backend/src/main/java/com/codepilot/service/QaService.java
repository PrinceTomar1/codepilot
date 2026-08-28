package com.codepilot.service;

import com.codepilot.dto.ai.AiCitation;
import com.codepilot.dto.ai.AiHistoryTurn;
import com.codepilot.dto.ai.AiQueryRequest;
import com.codepilot.dto.ai.AiQueryResponse;
import com.codepilot.dto.qa.AskResponse;
import com.codepilot.dto.qa.CitationDto;
import com.codepilot.dto.qa.QaHistoryDto;
import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.QaHistory;
import com.codepilot.entity.User;
import com.codepilot.exception.ApiException;
import com.codepilot.repository.QaHistoryRepository;
import com.codepilot.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QaService {

    private static final int TOP_K = 8;
    private static final int MAX_HISTORY_TURNS = 6;
    private static final int MAX_HISTORY_ANSWER_CHARS = 500;

    private final RepositoryService repositoryService;
    private final UserRepository userRepository;
    private final QaHistoryRepository qaHistoryRepository;
    private final AiServiceClient aiServiceClient;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    @Transactional
    public AskResponse ask(UUID userId, UUID repositoryId, String question) {
        CodeRepository repo = repositoryService.findOwned(userId, repositoryId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found"));

        List<AiHistoryTurn> history = recentHistory(repositoryId);

        // A cached answer was generated with no conversation context, so it's only safe to reuse
        // when this question is itself context-free (no prior turns) -- otherwise a follow-up
        // like "does it handle errors?" could return another conversation's unrelated cached
        // answer to the same literal question text.
        AskResponse response = history.isEmpty()
                ? cacheService.getQaAnswer(repositoryId, question).orElse(null)
                : null;

        if (response == null) {
            AiQueryResponse aiResponse =
                    aiServiceClient.query(new AiQueryRequest(repositoryId, question, TOP_K, history));
            List<CitationDto> citations = aiResponse.citations() == null ? List.of() :
                    aiResponse.citations().stream()
                            .map(this::toCitationDto)
                            .toList();
            response = new AskResponse(aiResponse.answer(), citations, aiResponse.chunksRetrieved());
            if (history.isEmpty()) {
                cacheService.putQaAnswer(repositoryId, question, response);
            }
        }

        QaHistory entry = QaHistory.builder()
                .repository(repo)
                .user(user)
                .question(question)
                .answer(response.answer())
                .citations(objectMapper.valueToTree(response.citations()))
                .build();
        qaHistoryRepository.save(entry);

        return response;
    }

    @Transactional(readOnly = true)
    public List<QaHistoryDto> history(UUID userId, UUID repositoryId) {
        repositoryService.findOwned(userId, repositoryId);
        return qaHistoryRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId).stream()
                .map(this::toHistoryDto)
                .toList();
    }

    private List<AiHistoryTurn> recentHistory(UUID repositoryId) {
        List<QaHistory> mostRecentFirst = qaHistoryRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId);
        List<QaHistory> lastN = mostRecentFirst.subList(0, Math.min(MAX_HISTORY_TURNS, mostRecentFirst.size()));
        return lastN.stream()
                .sorted(Comparator.comparing(QaHistory::getCreatedAt))
                .map(h -> new AiHistoryTurn(h.getQuestion(), truncate(h.getAnswer())))
                .toList();
    }

    private String truncate(String answer) {
        if (answer == null || answer.length() <= MAX_HISTORY_ANSWER_CHARS) {
            return answer;
        }
        return answer.substring(0, MAX_HISTORY_ANSWER_CHARS) + "...";
    }

    private CitationDto toCitationDto(AiCitation c) {
        return new CitationDto(c.filePath(), c.startLine(), c.endLine(), c.snippet());
    }

    private QaHistoryDto toHistoryDto(QaHistory h) {
        JsonNode citations = h.getCitations();
        return new QaHistoryDto(h.getId(), h.getQuestion(), h.getAnswer(), citations, h.getCreatedAt());
    }
}
