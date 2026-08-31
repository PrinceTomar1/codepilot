package com.codepilot.service;

import com.codepilot.dto.ai.AiQueryRequest;
import com.codepilot.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the REAL WebClient + Reactor retry pipeline against a real local HTTP server --
 * a Mockito-mocked AiServiceClient/WebClient can't reproduce Reactor's retry-exhaustion wrapping
 * behavior, which is exactly the bug this class guards against (a real 503 "LLM not configured"
 * response was getting swallowed and replaced with a meaningless "Retries exhausted: 2/2" message).
 */
class AiServiceClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AiServiceClient clientFor(HttpServer server) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .clientConnector(new ReactorClientHttpConnector())
                .build();
        // Same client for both params here: these tests exercise post()'s error-handling, not the
        // review-specific longer timeout (that's a real HttpClient/connector-level setting, not
        // something a local mock server test would observe).
        return new AiServiceClient(webClient, webClient, new ObjectMapper());
    }

    @Test
    void serviceUnavailableSurfacesRealMessageNotRetryExhausted() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query", exchange -> {
            callCount.incrementAndGet();
            String body = "{\"error\":\"LLM not configured: set ANTHROPIC_API_KEY\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AiServiceClient client = clientFor(server);

        assertThatThrownBy(() -> client.query(new AiQueryRequest(UUID.randomUUID(), "test question", 8, java.util.List.of())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getMessage()).isEqualTo("LLM not configured: set ANTHROPIC_API_KEY");
                    assertThat(apiEx.getMessage()).doesNotContain("Retries exhausted");
                });

        // 503 must NOT be retried -- it's a stable "not configured" signal, not a transient failure.
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void rateLimitedSurfacesRealMessageAndIsNotRetried() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query", exchange -> {
            callCount.incrementAndGet();
            String body = "{\"error\":\"Gemini rate limit / quota exceeded: Please retry in 33s\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AiServiceClient client = clientFor(server);

        assertThatThrownBy(() -> client.query(new AiQueryRequest(UUID.randomUUID(), "test question", 8, java.util.List.of())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(apiEx.getMessage()).contains("retry in 33s");
                });

        // A 429 won't resolve itself in a 2-second backoff window either -- don't retry it.
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void transientServerErrorIsRetriedThenSurfacesRealStatus() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query", exchange -> {
            callCount.incrementAndGet();
            byte[] bytes = "{\"error\":\"Database error\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(502, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AiServiceClient client = clientFor(server);

        assertThatThrownBy(() -> client.query(new AiQueryRequest(UUID.randomUUID(), "test question", 8, java.util.List.of())))
                .isInstanceOf(Exception.class)
                .satisfies(ex -> assertThat(ex.getMessage()).contains("Database error"));

        // A genuinely transient 502 SHOULD be retried: 1 initial + 2 retries = 3 calls.
        assertThat(callCount.get()).isEqualTo(3);
    }
}
