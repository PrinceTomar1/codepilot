package com.codepilot.service;

import com.codepilot.dto.ai.AiHistoryTurn;
import com.codepilot.dto.ai.AiQueryRequest;
import com.codepilot.dto.ai.AiQueryResponse;
import com.codepilot.dto.qa.AskResponse;
import com.codepilot.dto.qa.CitationDto;
import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.QaHistory;
import com.codepilot.entity.User;
import com.codepilot.repository.QaHistoryRepository;
import com.codepilot.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Conversation memory changed two things that are easy to get subtly wrong: (1) recent Q&A
 * turns must reach the AI service in chronological order, and (2) the Redis answer cache -- keyed
 * only on (repositoryId, question) -- must be skipped whenever history is involved, since two
 * different conversations asking the same literal follow-up ("does it handle errors?") should
 * never share a cached answer meant for a different prior context.
 */
class QaServiceTest {

    private RepositoryService repositoryService;
    private UserRepository userRepository;
    private QaHistoryRepository qaHistoryRepository;
    private AiServiceClient aiServiceClient;
    private CacheService cacheService;
    private QaTurnRecorder qaTurnRecorder;
    private QaService qaService;

    private final UUID userId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repositoryService = mock(RepositoryService.class);
        userRepository = mock(UserRepository.class);
        qaHistoryRepository = mock(QaHistoryRepository.class);
        aiServiceClient = mock(AiServiceClient.class);
        cacheService = mock(CacheService.class);
        qaTurnRecorder = mock(QaTurnRecorder.class);

        qaService = new QaService(
                repositoryService, userRepository, qaHistoryRepository, aiServiceClient, cacheService,
                new ObjectMapper(), qaTurnRecorder, Runnable::run);

        CodeRepository repo = CodeRepository.builder().id(repositoryId).build();
        User user = User.builder().id(userId).email("dev@example.com").build();
        when(repositoryService.findOwned(userId, repositoryId)).thenReturn(repo);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(qaHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void freshConversationUsesCacheAndSendsNoHistory() {
        when(qaHistoryRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId)).thenReturn(List.of());
        AskResponse cached = new AskResponse("cached answer", List.of(), 0);
        when(cacheService.getQaAnswer(repositoryId, "What does foo do?")).thenReturn(Optional.of(cached));

        AskResponse result = qaService.ask(userId, repositoryId, "What does foo do?");

        assertThat(result.answer()).isEqualTo("cached answer");
        verify(aiServiceClient, never()).query(any());
    }

    @Test
    void freshConversationCacheMissCallsAiServiceWithEmptyHistoryAndCachesResult() {
        when(qaHistoryRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId)).thenReturn(List.of());
        when(cacheService.getQaAnswer(repositoryId, "What does foo do?")).thenReturn(Optional.empty());
        when(aiServiceClient.query(any())).thenReturn(new AiQueryResponse("foo returns 42", List.of(), 1));

        AskResponse result = qaService.ask(userId, repositoryId, "What does foo do?");

        assertThat(result.answer()).isEqualTo("foo returns 42");
        verify(aiServiceClient).query(argThatHistoryIs(List.of()));
        verify(cacheService).putQaAnswer(eq(repositoryId), eq("What does foo do?"), any());
    }

    @Test
    void followUpQuestionSendsChronologicalHistoryAndBypassesCacheEntirely() {
        QaHistory older = QaHistory.builder()
                .question("What is foo.py?").answer("It's a helper module.")
                .createdAt(Instant.now().minus(2, ChronoUnit.MINUTES)).build();
        QaHistory newer = QaHistory.builder()
                .question("What does foo() do?").answer("It returns 42.")
                .createdAt(Instant.now().minus(1, ChronoUnit.MINUTES)).build();
        // Repository returns most-recent-first, as the real findByRepositoryIdOrderByCreatedAtDesc does.
        when(qaHistoryRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId))
                .thenReturn(List.of(newer, older));
        when(aiServiceClient.query(any())).thenReturn(new AiQueryResponse("No, it doesn't.", List.of(), 1));

        qaService.ask(userId, repositoryId, "Does it handle errors?");

        verify(aiServiceClient).query(argThatHistoryIs(List.of(
                new AiHistoryTurn("What is foo.py?", "It's a helper module."),
                new AiHistoryTurn("What does foo() do?", "It returns 42."))));
        verify(cacheService, never()).getQaAnswer(any(), any());
        verify(cacheService, never()).putQaAnswer(any(), any(), any());
    }

    private AiQueryRequest argThatHistoryIs(List<AiHistoryTurn> expected) {
        return org.mockito.ArgumentMatchers.argThat(req -> req != null && req.history().equals(expected));
    }

    // --- Streaming (askStream / relayStream) --------------------------------

    /** Captures the raw SSE text the service writes, so a test can assert what reached the client. */
    private static final class RecordingSseEmitter extends SseEmitter {
        final StringBuilder raw = new StringBuilder();
        boolean completed;

        @Override
        public void send(SseEventBuilder builder) {
            builder.build().forEach(d -> raw.append(d.getData()));
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    @Test
    void relayStreamForwardsFramesAndPersistsTheFinalTurn() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        when(aiServiceClient.queryStream(any())).thenReturn(Flux.just(
                ServerSentEvent.<String>builder().event("token").data("{\"text\": \"foo \"}").build(),
                ServerSentEvent.<String>builder().event("token").data("{\"text\": \"bar\"}").build(),
                ServerSentEvent.<String>builder().event("done")
                        .data("{\"answer\": \"foo bar\", \"citations\": [], \"chunksRetrieved\": 3}").build()));

        qaService.relayStream(emitter, userId, repositoryId, "what is foo?", List.of(), true);

        assertThat(emitter.raw.toString()).contains("token").contains("foo ").contains("bar");
        assertThat(emitter.raw.toString()).contains("done");
        assertThat(emitter.completed).isTrue();

        verify(qaTurnRecorder).record(eq(repositoryId), eq(userId), eq("what is foo?"),
                eq("foo bar"), any(JsonNode.class));
        verify(cacheService).putQaAnswer(eq(repositoryId), eq("what is foo?"),
                eq(new AskResponse("foo bar", List.of(), 3)));
    }

    @Test
    void relayStreamParsesCitationsFromTheDoneFrame() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        when(aiServiceClient.queryStream(any())).thenReturn(Flux.just(
                ServerSentEvent.<String>builder().event("token").data("{\"text\": \"see src/foo.py\"}").build(),
                ServerSentEvent.<String>builder().event("done").data(
                        "{\"answer\": \"see src/foo.py\", \"citations\": "
                                + "[{\"filePath\": \"src/foo.py\", \"startLine\": 1, \"endLine\": 5, \"snippet\": \"x\"}], "
                                + "\"chunksRetrieved\": 1}").build()));

        qaService.relayStream(emitter, userId, repositoryId, "where is foo?", List.of(), false);

        AskResponse expected = new AskResponse("see src/foo.py",
                List.of(new CitationDto("src/foo.py", 1, 5, "x")), 1);
        verify(qaTurnRecorder).record(eq(repositoryId), eq(userId), eq("where is foo?"),
                eq("see src/foo.py"), any(JsonNode.class));
        // history was non-empty on this call -> must NOT cache
        verify(cacheService, never()).putQaAnswer(any(), any(), any());
    }

    @Test
    void askStreamServesACachedAnswerWithoutCallingTheAiServiceStream() {
        when(qaHistoryRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId)).thenReturn(List.of());
        AskResponse cached = new AskResponse("cached answer", List.of(), 0);
        when(cacheService.getQaAnswer(repositoryId, "what is foo?")).thenReturn(Optional.of(cached));

        SseEmitter emitter = qaService.askStream(userId, repositoryId, "what is foo?");

        assertThat(emitter).isNotNull();
        verify(repositoryService).findOwned(userId, repositoryId);
        verify(aiServiceClient, never()).queryStream(any());
        verify(qaTurnRecorder).record(eq(repositoryId), eq(userId), eq("what is foo?"),
                eq("cached answer"), any(JsonNode.class));
    }

    @Test
    void askStreamRelaysFromTheAiServiceOnACacheMiss() {
        when(qaHistoryRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId)).thenReturn(List.of());
        when(cacheService.getQaAnswer(repositoryId, "what is foo?")).thenReturn(Optional.empty());
        when(aiServiceClient.queryStream(any())).thenReturn(Flux.just(
                ServerSentEvent.<String>builder().event("token").data("{\"text\": \"foo bar\"}").build(),
                ServerSentEvent.<String>builder().event("done")
                        .data("{\"answer\": \"foo bar\", \"citations\": [], \"chunksRetrieved\": 1}").build()));

        qaService.askStream(userId, repositoryId, "what is foo?");

        verify(aiServiceClient).queryStream(any());
        verify(qaTurnRecorder).record(eq(repositoryId), eq(userId), eq("what is foo?"),
                eq("foo bar"), any(JsonNode.class));
        verify(cacheService).putQaAnswer(eq(repositoryId), eq("what is foo?"),
                eq(new AskResponse("foo bar", List.of(), 1)));
    }

    @Test
    void relayStreamTurnsAnUpstreamApiExceptionIntoAnErrorFrame() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        when(aiServiceClient.queryStream(any())).thenReturn(Flux.error(
                new com.codepilot.exception.ApiException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "retry in 33s")));

        qaService.relayStream(emitter, userId, repositoryId, "explain this", List.of(), true);

        assertThat(emitter.raw.toString()).contains("error").contains("retry in 33s").contains("429");
        assertThat(emitter.completed).isTrue();
        verify(qaTurnRecorder, never()).record(any(), any(), any(), any(), any());
    }
}
