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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    private static final int TOP_K = 8;
    private static final int MAX_HISTORY_TURNS = 6;
    private static final int MAX_HISTORY_ANSWER_CHARS = 500;
    /** Overall ceiling for one streamed answer -- matches the AI service's own LLM call budget. */
    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final RepositoryService repositoryService;
    private final UserRepository userRepository;
    private final QaHistoryRepository qaHistoryRepository;
    private final AiServiceClient aiServiceClient;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    private final QaTurnRecorder qaTurnRecorder;
    private final Executor qaStreamExecutor;

    public QaService(RepositoryService repositoryService,
                     UserRepository userRepository,
                     QaHistoryRepository qaHistoryRepository,
                     AiServiceClient aiServiceClient,
                     CacheService cacheService,
                     ObjectMapper objectMapper,
                     QaTurnRecorder qaTurnRecorder,
                     @Qualifier("qaStreamExecutor") Executor qaStreamExecutor) {
        this.repositoryService = repositoryService;
        this.userRepository = userRepository;
        this.qaHistoryRepository = qaHistoryRepository;
        this.aiServiceClient = aiServiceClient;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
        this.qaTurnRecorder = qaTurnRecorder;
        this.qaStreamExecutor = qaStreamExecutor;
    }

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

    /**
     * Streaming counterpart to {@link #ask}: returns an {@link SseEmitter} that relays the AI
     * service's token-by-token SSE stream to the browser, then persists the completed turn.
     *
     * Frames forwarded to the client (same names the AI service emits):
     *   token  {"text": "..."}                          -- incremental answer text
     *   done   {"answer","citations","chunksRetrieved"} -- authoritative final payload
     *   error  {"error","status"}                       -- AI provider unavailable / rate-limited
     *
     * Ownership + user checks and history assembly happen synchronously on the request thread
     * (so a 403/404 is a normal error response, not a half-open stream); the relay itself runs on
     * {@code qaStreamExecutor}.
     */
    public SseEmitter askStream(UUID userId, UUID repositoryId, String question) {
        repositoryService.findOwned(userId, repositoryId);
        userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found"));

        List<AiHistoryTurn> history = recentHistory(repositoryId);
        boolean cacheable = history.isEmpty();

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onError(err -> log.debug("Q&A stream emitter error: {}", err.toString()));

        AskResponse cached = cacheable
                ? cacheService.getQaAnswer(repositoryId, question).orElse(null)
                : null;

        if (cached != null) {
            qaStreamExecutor.execute(() -> emitCachedAnswer(emitter, userId, repositoryId, question, cached));
        } else {
            qaStreamExecutor.execute(() ->
                    relayStream(emitter, userId, repositoryId, question, history, cacheable));
        }
        return emitter;
    }

    /** A cache hit still streams -- one token frame with the whole answer, then the done frame -- so
     * the client code path is identical whether or not the answer was cached. */
    private void emitCachedAnswer(SseEmitter emitter, UUID userId, UUID repositoryId, String question,
                                   AskResponse cached) {
        try {
            emitter.send(SseEmitter.event().name("token")
                    .data(objectMapper.writeValueAsString(java.util.Map.of("text", cached.answer())),
                            MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("done")
                    .data(objectMapper.writeValueAsString(cached), MediaType.APPLICATION_JSON));
            recordTurn(userId, repositoryId, question, cached);
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    void relayStream(SseEmitter emitter, UUID userId, UUID repositoryId, String question,
                     List<AiHistoryTurn> history, boolean cacheable) {
        AtomicReference<AskResponse> finalResponse = new AtomicReference<>();
        try {
            aiServiceClient.queryStream(new AiQueryRequest(repositoryId, question, TOP_K, history))
                    .toStream()
                    .forEach(event -> forwardEvent(emitter, event, finalResponse));
        } catch (ApiException e) {
            // ai-service's deliberate 503 ("LLM not configured") / 429 (provider rate-limited) --
            // deliver it as an error frame the frontend can show, not a broken stream.
            sendErrorFrame(emitter, e.getStatus().value(), e.getMessage());
            emitter.complete();
            return;
        } catch (StreamClosedException e) {
            // Client hung up mid-stream -- nothing left to send.
            emitter.complete();
            return;
        } catch (Exception e) {
            log.warn("Q&A stream relay failed for repository {}: {}", repositoryId, e.toString());
            sendErrorFrame(emitter, 502, "The assistant hit an unexpected error. Please try again.");
            emitter.complete();
            return;
        }

        AskResponse response = finalResponse.get();
        if (response != null) {
            recordTurn(userId, repositoryId, question, response);
            if (cacheable) {
                cacheService.putQaAnswer(repositoryId, question, response);
            }
        }
        emitter.complete();
    }

    private void forwardEvent(SseEmitter emitter, org.springframework.http.codec.ServerSentEvent<String> event,
                              AtomicReference<AskResponse> finalResponse) {
        String name = event.event() == null ? "message" : event.event();
        String data = event.data() == null ? "" : event.data();
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new StreamClosedException(e);
        }
        if ("done".equals(name) && !data.isBlank()) {
            try {
                finalResponse.set(objectMapper.readValue(data, AskResponse.class));
            } catch (Exception e) {
                log.warn("Could not parse 'done' frame from ai-service: {}", e.toString());
            }
        }
    }

    private void sendErrorFrame(SseEmitter emitter, int status, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(objectMapper.writeValueAsString(java.util.Map.of("error", message, "status", status)),
                            MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // Client already gone -- the emitter.complete() in the caller is all that's left to do.
        }
    }

    private void recordTurn(UUID userId, UUID repositoryId, String question, AskResponse response) {
        try {
            qaTurnRecorder.record(repositoryId, userId, question, response.answer(),
                    objectMapper.valueToTree(response.citations()));
        } catch (Exception e) {
            // The answer was already delivered; a failed history write shouldn't surface to the user.
            log.warn("Failed to persist streamed Q&A turn for repository {}: {}", repositoryId, e.toString());
        }
    }

    /** Signals that the client closed the SSE connection while the relay was still running. */
    private static final class StreamClosedException extends RuntimeException {
        StreamClosedException(Throwable cause) {
            super(cause);
        }
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
